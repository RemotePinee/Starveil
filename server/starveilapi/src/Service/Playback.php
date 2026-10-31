<?php
/**
 * 付费墙与观看数据。
 *
 * 闸门只有一个开关 `paywall_enabled`。下发给客户端的字段与旧接口
 * `v1.user/checkVodTrySee` 保持一致：`status` / `trysee` / `user_video`。
 * 其中 `trysee` 已废弃（恒 0），保留键位只是因为客户端还在读它；试看改由
 * `trysee_seconds` 表达，且**只在 CDN 接通时**才可能非 0（见 [trySee]）。
 */

declare(strict_types=1);

namespace Starveil\Api\Service;

use Starveil\Api\Installer;
use Starveil\Api\Repo\Orders;
use Starveil\Api\Repo\Plog;
use Starveil\Api\Repo\Users;
use Starveil\Api\Support\AdReward;
use Starveil\Api\Support\CdnUrlSigner;
use Starveil\Api\Support\Setting;
use think\Db;

final class Playback
{
    /**
     * 本请求内的会员判定缓存：详情页要判两次（整剧闸门 + 逐集解锁），别为同一个人查两遍用户表。
     *
     * @var array<int,bool>
     */
    private array $vipCache = [];

    public function __construct(
        private Users $users = new Users(),
        private Orders $orders = new Orders(),
    ) {
    }

    /**
     * 付费墙闸门（按集）。
     *
     * 只有一个开关：`paywall_enabled`。
     * - 关：一律 status=0，任何登录状态都拿完整播放地址，下面三处出口都不参与判定。
     * - 开：未解锁用户 status=1。解锁出口由三处**已有**配置分别决定，同时生效——
     *   会员（`member_enabled` + `vip_group_id`，见 [isVip]）、
     *   广告（激励位已配，见 [rewardAdConfigured]）、积分（`episode_points > 0`）。
     *
     * 已解锁的用户（会员 / 整剧单 / 已购这一集 / 看完广告）不管开关怎么设都是 status=0。
     *
     * status 只发 0 / 1：客户端 `TrySeeRules.access()` 把 1 当"需 VIP"、2 当"需单集付费"，
     * 而"能走哪几种解锁方式"现在由 `can_vip` / `can_points` / `can_ad` 三个能力位回答，
     * 客户端按位画按钮，不靠 status 区分。2 因此不再下发。
     *
     * 试看**只在 CDN 这条路上存在**：`trysee` 仍恒 0（客户端 `TrySeeDto.trySeeMinutes`
     * 还在读这个键，删键会让它走解析默认值之外的路径），真正的试看秒数放进新键
     * `trysee_seconds`（= `cdn_trysee_minutes × 60`；CDN 关、没配试看、或用户已解锁时为 0）。
     * 没有 CDN 就没有试看——不按集试看，也不因为填了分钟数就放行。
     *
     * 会员体系关闭只是停止售卖，**历史会员仍按到期时间继续享受权益**：先走 isVip 分支。
     * 三种解锁方式一个都没配时，开关一开就是全站锁死——后台分组说明已写明要至少配一种，
     * 这里不做"没配就自动免费"的隐藏兜底，否则运营会以为关掉会员就等于免费。
     *
     * `$episodeNumber` 决定按哪个口径判解锁：
     * - `> 0`：按「用户 + 剧 + 集」判——广告解锁、单集购买，以及会员 / 整剧单；
     * - `= 0`：调用方不知道第几集，**只按「会员 / 整剧单」判**，绝不查单集订单。
     *   集号 0 在订单表里是"整剧单"的写法（加列之前的老单），拿它去查"任意已付单"会把
     *   买过一集的人当成整剧已购——详情页曾经就是这么把整部剧送出去的。
     */
    public function trySee(?int $userId, int $vodId, int $episodeNumber = 0): array
    {
        $paywall = Setting::int('paywall_enabled', 0) === 1;
        $memberEnabled = Setting::int('member_enabled', 1) === 1;
        $pointsPrice = max(Setting::int('episode_points', 0), 0);

        // 试看只在 CDN 接通时才有值：CDN 关着时 minutes 不参与任何判定，和"没配"等价
        $tryseeSeconds = CdnUrlSigner::fromSettings()?->previewSeconds() ?? 0;

        if (!$paywall) {
            return $this->gate(0, $pointsPrice, $memberEnabled, $tryseeSeconds);
        }

        // 会员体系关闭只是"停止售卖"，不是取消会员：在期会员由下面的 isVip() 照常放行。
        // 但如果既没配积分也没配广告位，非会员就一个解锁入口都没有——锁死没有意义，
        // 按免费处理。对应旧 trysee_status 时代那条"会员关闭 + 需会员 → 转免费/积分"。
        if (!$memberEnabled && $pointsPrice === 0 && !$this->rewardAdConfigured()) {
            return $this->gate(0, $pointsPrice, $memberEnabled, $tryseeSeconds);
        }

        // 广告解锁（平台 SSV 回调 / 直投看完）与单集购买按集生效：先放行，再谈会员与积分。
        // 少了这一步，用户看完广告重查闸门时服务端仍答"需解锁"，这次广告就白看了。
        if ($episodeNumber > 0) {
            if ($this->unlockedEpisodes($userId, $vodId, [$episodeNumber])[$episodeNumber] ?? false) {
                return $this->gate(0, $pointsPrice, $memberEnabled, $tryseeSeconds);
            }
        } elseif ($this->ownsWholeDrama($userId, $vodId)) {
            return $this->gate(0, $pointsPrice, $memberEnabled, $tryseeSeconds);
        }

        return $this->gate(1, $pointsPrice, $memberEnabled, $tryseeSeconds);
    }

    /**
     * 整剧口径的闸门：这部片**整体**要不要解锁（详情页的 `play_locked` 用它）。
     *
     * 就是集号为 0 的 [trySee]：只认会员与整剧单，不把"买过任意一集"当整剧已购。
     * 逐集能不能播按 nid 另判（见 [unlockedEpisodes]），两处共用同一套事实。
     */
    public function trySeeWholeDrama(?int $userId, int $vodId): array
    {
        return $this->trySee($userId, $vodId, 0);
    }

    /**
     * 闸门结果。`can_vip` / `can_points` / `can_ad` 是给播放器浮层的按钮能力位，
     * 客户端按它们渲染"开通会员 / 积分兑换 / 看广告"三种出口，不再自己猜。
     *
     * `can_ad` 只表示"后台配了激励位"，与客户端的 `AdSlotDto.isEnabled` 同口径：
     * 总开关开，且位 ID 或素材非空。是否放行不由这里决定——广告解锁走 [unlockedEpisodes]，
     * 解锁成功时上游已经用 status=0 调进来，`can_ad` 因而自然为 0。
     *
     * `trysee` 恒 0：键位留着只为客户端仍在读。真正的试看时长走 `trysee_seconds`，
     * 而且只在 `status != 0` 时下发：已解锁用户本来就能看完整剧集，给他一个非 0 的
     * "试看秒数"只会让客户端对着会员提示"试看 X 分钟"。
     *
     * @return array{status:int,trysee:int,trysee_seconds:int,user_video:int,member_enabled:int,points_price:int,can_vip:int,can_points:int,can_ad:int}
     */
    private function gate(int $status, int $pointsPrice, bool $memberEnabled, int $tryseeSeconds): array
    {
        $blocked = $status !== 0;
        return [
            'status' => $status,
            'trysee' => 0,
            'trysee_seconds' => $blocked ? max(0, $tryseeSeconds) : 0,
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
     * 整剧口径：这名用户是否已经拥有整部片（会员 / 整剧单）。
     *
     * 详情页的 `play_locked` 与集号为 0 的闸门走这里。**不看单集订单**：单集订单在集号为 0
     * 时不过滤集号（见 `Repo\Orders::hasPaidEpisode()`），拿它当整剧口径就是"买一集解锁全剧"。
     */
    public function ownsWholeDrama(?int $userId, int $vodId): bool
    {
        if ($userId === null || $userId <= 0 || $vodId <= 0) {
            return false;
        }
        if ($this->isVip($userId)) {
            return true;
        }

        return $this->orders->paidEpisodes($userId, $vodId)['whole'];
    }

    /**
     * 逐集解锁状态（批量）：详情页一次判完整部剧。
     *
     * 与 [trySee] 的集号分支完全同源——会员 / 整剧单 / 单集购买 / 广告解锁，任一成立即算
     * 已解锁，所以同一集在详情页与 `v1.vod/playUrl` 的结论必然一致。逐集调 [trySee] 也能得到
     * 同样结果，但那会为每集重查一次用户与订单（短剧动辄上百集）；这里把「已购集号」与
     * 「广告解锁集号」各用一条 SQL 取回。
     *
     * @param array<int,int> $episodeNumbers
     * @return array<int,bool> nid => 已解锁
     */
    public function unlockedEpisodes(?int $userId, int $vodId, array $episodeNumbers): array
    {
        $status = [];
        foreach ($episodeNumbers as $number) {
            $status[(int)$number] = false;
        }
        if ($status === [] || $userId === null || $userId <= 0 || $vodId <= 0) {
            return $status;
        }
        if ($this->isVip($userId)) {
            return array_fill_keys(array_keys($status), true);
        }
        $paid = $this->orders->paidEpisodes($userId, $vodId);
        if ($paid['whole']) {
            return array_fill_keys(array_keys($status), true);
        }
        $purchased = array_fill_keys($paid['episodes'], true);
        $adUnlocked = $this->unlockedEpisodeNumbers($userId, $vodId);
        foreach (array_keys($status) as $nid) {
            $status[$nid] = isset($purchased[$nid]) || isset($adUnlocked[$nid]);
        }

        return $status;
    }

    /**
     * 这名用户在这部剧里被广告解锁过的集号（一条 SQL）。
     *
     * 与 [unlockedEpisodes] 同口径：表里没有过期列，广告解锁按集永久有效；不按 `unlock_type`
     * 过滤（将来积分兑换也写这张表时同样应该放行）；查询失败一律当"未解锁"——插件可能还没跑
     * v2.2.0 迁移（表不存在），这时首页与播放页必须照常工作，不能把一个可选能力打成 500。
     *
     * @return array<int,true>
     */
    private function unlockedEpisodeNumbers(int $userId, int $vodId): array
    {
        try {
            $rows = Db::table(Installer::prefix() . 'cuican_shortplay_unlock')
                ->where('user_id', $userId)
                ->where('vod_id', $vodId)
                ->column('episode_number');
        } catch (\Throwable $e) {
            return [];
        }
        $unlocked = [];
        foreach ($rows as $number) {
            $unlocked[(int)$number] = true;
        }

        return $unlocked;
    }

    /**
     * 会员判定：`mac_user.group_id` 是逗号串，取最高等级与 `vip_group_id` 比大小。
     *
     * macCMS 网页端用的就是 `max(group_id) >= 3`（`All.php` 的 check_user_popedom），
     * 这里必须同口径。等值匹配 `in_array()` 会把 `1,4`（VIP4）判成非会员，
     * 于是同一个账号在网页是会员、在 App 不是。
     *
     * 到期口径同样对齐核心：`user_end_time > time()` 才算会员，**0 视为已过期**。
     * 核心 `application/common/model/User.php` 两处会员判定（507、784）都这么写；把 0 当永久
     * 会让同一账号在网页已降级、在 App 仍免费。插件自己开通会员的路径
     * （`Repo\Users::extendMembership()`）一定写正数到期时间，不存在"故意用 0 表示永久"的数据。
     */
    public function isVip(?int $userId): bool
    {
        if ($userId === null || $userId <= 0) {
            return false;
        }
        if (array_key_exists($userId, $this->vipCache)) {
            return $this->vipCache[$userId];
        }
        $user = $this->users->findById($userId);
        if (!$user) {
            return $this->vipCache[$userId] = false;
        }
        $vipGroupId = Setting::int('vip_group_id', 3);
        // App 只有一种会员：等级 >= vip_group_id 都算，与 macCMS 的 max(group_id) >= 3 对齐。
        $groups = array_map('intval', array_map('trim', explode(',', (string)($user['group_id'] ?? ''))));
        $level = $groups === [] ? 0 : max($groups);
        $endTime = (int)($user['user_end_time'] ?? 0);

        return $this->vipCache[$userId] = $level >= $vipGroupId && $endTime > time();
    }

    /** 每天最多计入的观看秒数。客户端自报时长，不封顶就是一个刷积分入口。 */
    private const MAX_VIEW_SECONDS_PER_DAY = 86400;

    /**
     * 观看时长累加（秒）。按 2 倍上报，这里只记真实值。
     *
     * `view_seconds_award_step` 的语义是「看满多少秒奖励 1 积分」的步长（0 = 不发，默认）。
     * 旧实现把配置值原样回给客户端当积分，却既不写 `user_points` 也不写 `mac_plog`：
     * 客户端以为拿到积分，库里一分没有。现在按跨过的完整步数真实入账并记日志。
     *
     * @return int 本次实际发放的积分
     */
    public function addViewSeconds(int $userId, int $seconds): int
    {
        if ($userId <= 0 || $seconds <= 0) {
            return 0;
        }
        $seconds = $this->countableViewSeconds($userId, $seconds);
        if ($seconds <= 0) {
            return 0;
        }
        $ext = $this->users->ensureExt($userId);
        $before = (int)($ext['view_seconds'] ?? 0);
        Db::table(Installer::prefix() . 'cuican_user_ext')
            ->where('user_id', $userId)
            ->inc('view_seconds', $seconds)
            ->update(['updated_at' => time()]);

        return $this->awardViewSeconds($userId, $before, $before + $seconds);
    }

    /** 截到今日剩余额度并回写当日计数；额度用完返回 0（当天不再累加也不再发奖）。 */
    private function countableViewSeconds(int $userId, int $seconds): int
    {
        $key = 'view_seconds_' . $userId . '_' . date('Ymd');
        $used = (int)Setting::get($key, '0');
        $allow = min($seconds, 3600, self::MAX_VIEW_SECONDS_PER_DAY - $used);
        if ($allow <= 0) {
            return 0;
        }
        Setting::set($key, (string)($used + $allow));
        return $allow;
    }

    /**
     * 每跨过一个完整步长发 1 分。
     *
     * 用「前后各取一次整除再相减」，而不是「本次秒数 / 步长」：后者把同一段时长拆成
     * 多次上报时会反复发奖（每次都不满一步，但每次都算一次）。
     */
    private function awardViewSeconds(int $userId, int $before, int $after): int
    {
        $step = Setting::int('view_seconds_award_step', 0);
        if ($step <= 0) {
            return 0;
        }
        $earned = intdiv($after, $step) - intdiv($before, $step);
        if ($earned <= 0) {
            return 0;
        }
        $this->users->addPoints($userId, $earned, Plog::TYPE_TASK, '观看时长奖励');
        return $earned;
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
