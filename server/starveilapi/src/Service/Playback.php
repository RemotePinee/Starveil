<?php
/**
 * 试看与观看数据。
 *
 * 试看判定由服务端给结论（客户端只负责"何时算看超"），与旧接口
 * `v1.user/checkVodTrySee` 的字段保持一致：`status` / `trysee` / `user_video`。
 */

declare(strict_types=1);

namespace Starveil\Api\Service;

use Starveil\Api\Installer;
use Starveil\Api\Repo\Orders;
use Starveil\Api\Repo\Users;
use Starveil\Api\Support\AdReward;
use Starveil\Api\Support\Setting;
use think\Db;

final class Playback
{
    public function __construct(
        private Users $users = new Users(),
        private Orders $orders = new Orders(),
    ) {
    }

    /**
     * 试看闸门。
     *
     * status：0 免费 / 1 需会员 / 2 需积分兑换本集；trysee 单位分钟。
     * 会员或已兑换该片时按免费处理，客户端不用自己拼判断。
     *
     * 会员体系关闭只是停止售卖，**历史会员仍按到期时间继续享受权益**：
     * 先走 isVip 分支，再按下面的折算返回 status。
     *
     * 返回的 `can_vip` / `can_points` / `can_ad` 是给播放器浮层的按钮能力位，
     * 客户端按它们渲染"开通会员 / 积分兑换 / 看广告"三种出口，不再自己猜。
     *
     * `$episodeNumber` 只用来查广告解锁记录：解锁按「用户 + 剧 + 集」生效，
     * 传 0 表示调用方不知道第几集，此时只按会员/积分的旧口径判定。
     */
    public function trySee(?int $userId, int $vodId, int $episodeNumber = 0): array
    {
        $status = Setting::int('trysee_status', 0);
        $minutes = Setting::int('trysee_minutes', 6);
        $memberEnabled = Setting::int('member_enabled', 1) === 1;
        $pointsPrice = max(Setting::int('episode_points', 0), 0);

        // 会员关闭后，"需会员"不能再把用户卡死：配了积分价就改成积分兑换，没配价直接免费。
        if (!$memberEnabled && $status === 1) {
            $status = $pointsPrice > 0 ? 2 : 0;
        }

        // 广告解锁（平台 SSV 回调 / 直投看完）已经把这一集写进解锁表：先放行，再谈会员与积分。
        // 少了这一步，用户看完广告重查闸门时服务端仍答"需会员"，这次广告就白看了。
        if ($episodeNumber > 0 && $this->unlocked($userId, $vodId, $episodeNumber)) {
            return $this->gate(0, $minutes, $pointsPrice, $memberEnabled);
        }

        if ($status === 0) {
            return $this->gate(0, $minutes, $pointsPrice, $memberEnabled);
        }

        $isVip = $this->isVip($userId);
        if ($isVip || ($userId !== null && $this->orders->hasPaidEpisode($userId, $vodId))) {
            return $this->gate(0, $minutes, $pointsPrice, $memberEnabled);
        }

        return $this->gate($status, $minutes, $pointsPrice, $memberEnabled);
    }

    /**
     * 试看结果。`can_ad` 只表示"后台配了激励位"，与客户端的 `AdSlotDto.isEnabled` 同口径：
     * 总开关开，且位 ID 或素材非空。是否放行不由这里决定——广告解锁走 [unlocked]，
     * 解锁成功时上游已经用 status=0 调进来，`can_ad` 因而自然为 0。
     *
     * @return array{status:int,trysee:int,user_video:int,member_enabled:int,points_price:int,can_vip:int,can_points:int,can_ad:int}
     */
    private function gate(int $status, int $minutes, int $pointsPrice, bool $memberEnabled): array
    {
        $blocked = $status !== 0;
        return [
            'status' => $status,
            'trysee' => $minutes,
            'user_video' => 0,
            'member_enabled' => $memberEnabled ? 1 : 0,
            'points_price' => $pointsPrice,
            'can_vip' => $blocked && $memberEnabled ? 1 : 0,
            'can_points' => $blocked && $pointsPrice > 0 ? 1 : 0,
            'can_ad' => $blocked && $this->rewardAdConfigured() ? 1 : 0,
        ];
    }

    /**
     * 激励位是否配好：总开关开 + 位 ID 或素材非空。
     *
     * 素材有三种来源，任一到就算配好：聚合位 ID、直投图片、直投视频。
     * 少了视频这一条，纯直投（只传 mp4、不配底图）的站 `can_ad` 恒为 0，
     * 播放器不会出现「看广告解锁」按钮——而服务端明明能签发会话、也有片源可播。
     * 与 `Startup::slotConfigured()` 同一口径，两处必须一起改。
     *
     * 公开：`Controller\AdRewardController::postSession()` 签会话前要按同一口径挡一遍，
     * 位被关掉时不能还能开广告会话（那等于留了条免费解锁的路）。
     */
    public function rewardAdConfigured(): bool
    {
        if (Setting::int('ad_enabled', 0) !== 1) {
            return false;
        }
        return trim(Setting::get('ad_slot_reward', '')) !== ''
            || trim(Setting::get('ad_reward_image', '')) !== ''
            || AdReward::videoUrl() !== '';
    }

    /**
     * 该用户这一集有没有广告解锁记录。
     *
     * 表里没有过期列：广告解锁与付费买断同口径，按集永久有效。
     * 不按 `unlock_type` 过滤——将来积分兑换也写这张表时，同样应该放行。
     *
     * 查询失败一律当"未解锁"：插件可能还没跑 v2.2.0 迁移（表不存在），
     * 这时首页与播放页必须照常工作，绝不能因为一个可选能力把整页打成 500。
     */
    private function unlocked(?int $userId, int $vodId, int $episodeNumber): bool
    {
        if ($userId === null || $userId <= 0 || $vodId <= 0 || $episodeNumber <= 0) {
            return false;
        }
        try {
            $row = Db::table(Installer::prefix() . 'cuican_shortplay_unlock')
                ->where('user_id', $userId)
                ->where('vod_id', $vodId)
                ->where('episode_number', $episodeNumber)
                ->find();
            return (bool)$row;
        } catch (\Throwable $e) {
            return false;
        }
    }

    public function isVip(?int $userId): bool
    {
        if ($userId === null) {
            return false;
        }
        $user = $this->users->findById($userId);
        if (!$user) {
            return false;
        }
        $vipGroupId = (string)Setting::int('vip_group_id', 3);
        // macCMS 的 group_id 允许逗号分隔多组，不能直接 (int) 比较
        $groups = array_map('trim', explode(',', (string)($user['group_id'] ?? '')));
        $endTime = (int)($user['user_end_time'] ?? 0);
        return in_array($vipGroupId, $groups, true) && ($endTime === 0 || $endTime > time());
    }

    /** 观看时长累加（秒）。按 2 倍上报，这里只记真实值。 */
    public function addViewSeconds(int $userId, int $seconds): int
    {
        if ($userId <= 0 || $seconds <= 0) {
            return 0;
        }
        $seconds = min($seconds, 3600);
        $this->users->ensureExt($userId);
        Db::table(Installer::prefix() . 'cuican_user_ext')
            ->where('user_id', $userId)
            ->inc('view_seconds', $seconds)
            ->update(['updated_at' => time()]);

        return (int)Setting::int('view_seconds_award_step', 0);
    }

    /** 播放记录。同一部片只保留一条，进度覆盖，便于"继续观看"。 */
    public function saveViewLog(
        int $userId,
        int $vodId,
        int $sourceIndex,
        int $episodeIndex,
        int $progressSec,
    ): void {
        if ($userId <= 0 || $vodId <= 0) {
            return;
        }
        $table = Installer::prefix() . 'cuican_vlog';
        $exists = Db::table($table)->where('user_id', $userId)->where('vod_id', $vodId)->find();
        $data = [
            'source_index' => $sourceIndex,
            'episode_index' => $episodeIndex,
            'progress_sec' => max($progressSec, 0),
            'updated_at' => time(),
        ];
        if ($exists) {
            Db::table($table)->where('vlog_id', (int)$exists['vlog_id'])->update($data);
            return;
        }
        Db::table($table)->insert($data + ['user_id' => $userId, 'vod_id' => $vodId]);
    }
}
