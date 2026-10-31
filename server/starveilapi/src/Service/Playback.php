<?php
/**
 * 付费墙与观看数据。
 *
 * 闸门只有一个开关 `paywall_enabled`。下发给客户端的字段与旧接口
 * `v1.user/checkVodTrySee` 保持一致：`status` / `trysee` / `user_video`，
 * 其中 `trysee` 已废弃（恒 0），保留键位只是因为客户端还在读它。
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
     * 付费墙闸门。
     *
     * 只有一个开关：`paywall_enabled`。
     * - 关：一律 status=0，任何登录状态都拿完整播放地址，下面三处出口都不参与判定。
     * - 开：未解锁用户 status=1。解锁出口由三处**已有**配置分别决定，同时生效——
     *   会员（`member_enabled` + `vip_group_id`，见 [isVip]）、
     *   广告（激励位已配，见 [rewardAdConfigured]）、积分（`episode_points > 0`）。
     *
     * 已解锁的用户（会员 / 已购这一集 / 看完广告）不管开关怎么设都是 status=0。
     *
     * status 只发 0 / 1：客户端 `TrySeeRules.access()` 把 1 当"需 VIP"、2 当"需单集付费"，
     * 而"能走哪几种解锁方式"现在由 `can_vip` / `can_points` / `can_ad` 三个能力位回答，
     * 客户端按位画按钮，不靠 status 区分。2 因此不再下发。
     *
     * 试看时长与试看集数已下线：`trysee` 恒 0（客户端 `TrySeeDto.trySeeMinutes` 仍在读这个键，
     * 删键会让它走解析默认值之外的路径，故保留键、值恒 0）。
     *
     * 会员体系关闭只是停止售卖，**历史会员仍按到期时间继续享受权益**：先走 isVip 分支。
     * 三种解锁方式一个都没配时，开关一开就是全站锁死——后台分组说明已写明要至少配一种，
     * 这里不做"没配就自动免费"的隐藏兜底，否则运营会以为关掉会员就等于免费。
     *
     * `$episodeNumber` 只用来查广告解锁与单集购买：解锁按「用户 + 剧 + 集」生效，
     * 传 0 表示调用方不知道第几集，此时只按会员口径判定。
     */
    public function trySee(?int $userId, int $vodId, int $episodeNumber = 0): array
    {
        $paywall = Setting::int('paywall_enabled', 0) === 1;
        $memberEnabled = Setting::int('member_enabled', 1) === 1;
        $pointsPrice = max(Setting::int('episode_points', 0), 0);

        if (!$paywall) {
            return $this->gate(0, $pointsPrice, $memberEnabled);
        }

        // 会员体系关闭只是"停止售卖"，不是取消会员：在期会员由下面的 isVip() 照常放行。
        // 但如果既没配积分也没配广告位，非会员就一个解锁入口都没有——锁死没有意义，
        // 按免费处理。对应旧 trysee_status 时代那条"会员关闭 + 需会员 → 转免费/积分"。
        if (!$memberEnabled && $pointsPrice === 0 && !$this->rewardAdConfigured()) {
            return $this->gate(0, $pointsPrice, $memberEnabled);
        }

        // 广告解锁（平台 SSV 回调 / 直投看完）已经把这一集写进解锁表：先放行，再谈会员与积分。
        // 少了这一步，用户看完广告重查闸门时服务端仍答"需解锁"，这次广告就白看了。
        if ($episodeNumber > 0 && $this->unlocked($userId, $vodId, $episodeNumber)) {
            return $this->gate(0, $pointsPrice, $memberEnabled);
        }

        if ($this->isVip($userId)
            || ($userId !== null && $this->orders->hasPaidEpisode($userId, $vodId, $episodeNumber))) {
            return $this->gate(0, $pointsPrice, $memberEnabled);
        }

        return $this->gate(1, $pointsPrice, $memberEnabled);
    }

    /**
     * 闸门结果。`can_vip` / `can_points` / `can_ad` 是给播放器浮层的按钮能力位，
     * 客户端按它们渲染"开通会员 / 积分兑换 / 看广告"三种出口，不再自己猜。
     *
     * `can_ad` 只表示"后台配了激励位"，与客户端的 `AdSlotDto.isEnabled` 同口径：
     * 总开关开，且位 ID 或素材非空。是否放行不由这里决定——广告解锁走 [unlocked]，
     * 解锁成功时上游已经用 status=0 调进来，`can_ad` 因而自然为 0。
     *
     * `trysee` 恒 0：试看时长已下线，键位留着只为客户端仍在读。
     *
     * @return array{status:int,trysee:int,user_video:int,member_enabled:int,points_price:int,can_vip:int,can_points:int,can_ad:int}
     */
    private function gate(int $status, int $pointsPrice, bool $memberEnabled): array
    {
        $blocked = $status !== 0;
        return [
            'status' => $status,
            'trysee' => 0,
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

    /**
     * 会员判定：`mac_user.group_id` 是逗号串，取最高等级与 `vip_group_id` 比大小。
     *
     * macCMS 网页端用的就是 `max(group_id) >= 3`（`All.php` 的 check_user_popedom），
     * 这里必须同口径。等值匹配 `in_array()` 会把 `1,4`（VIP4）判成非会员，
     * 于是同一个账号在网页是会员、在 App 不是。
     *
     * `user_end_time = 0` 表示永久，否则按到期时间。
     */
    public function isVip(?int $userId): bool
    {
        if ($userId === null) {
            return false;
        }
        $user = $this->users->findById($userId);
        if (!$user) {
            return false;
        }
        $vipGroupId = Setting::int('vip_group_id', 3);
        // App 只有一种会员：等级 >= vip_group_id 都算，与 macCMS 的 max(group_id) >= 3 对齐。
        $groups = array_map('intval', array_map('trim', explode(',', (string)($user['group_id'] ?? ''))));
        $level = $groups === [] ? 0 : max($groups);
        $endTime = (int)($user['user_end_time'] ?? 0);
        return $level >= $vipGroupId && ($endTime === 0 || $endTime > time());
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
