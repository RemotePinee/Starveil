<?php
/**
 * 启动配置。
 *
 * 客户端（星幕）只认这套字段；皮肤、Tab、卡片列数都由这里下发，
 * 不再像历史插件那样把 44 个广告位字段全塞回来。
 */

declare(strict_types=1);

namespace Starveil\Api\Service;

use Starveil\Api\Installer;
use Starveil\Api\Repo\Push;
use Starveil\Api\Support\AdReward;
use Starveil\Api\Support\DetailFlags;
use Starveil\Api\Support\MacPay;
use Starveil\Api\Support\Media;
use Starveil\Api\Support\Setting;
use think\Db;

final class Startup
{
    /** 首页模块默认顺序；后台可改。 */
    private const DEFAULT_MODULES = ['banner', 'featured', 'links', 'blocks', 'hot', 'continue', 'topic', 'live', 'guess'];

    /** 底部菜单默认配置；老字段 `app_ui_tab_fun` 只作迁移兜底。 */
    private const DEFAULT_TABS = [
        ['id' => 'home', 'label' => '首页', 'icon' => 'home', 'visible' => true, 'order' => 1],
        ['id' => 'second', 'label' => '专题', 'icon' => 'topic', 'visible' => true, 'order' => 2, 'mode' => 'topic'],
        ['id' => 'rank', 'label' => '榜单', 'icon' => 'rank', 'visible' => true, 'order' => 3],
        ['id' => 'member', 'label' => '会员', 'icon' => 'vip', 'visible' => true, 'order' => 4],
        ['id' => 'mine', 'label' => '我的', 'icon' => 'user', 'visible' => true, 'order' => 5],
    ];

    /** @param int|null $userId 当前登录用户 id；0/null 表示未登录（startup 对游客开放）。 */
    public function __construct(private ?int $userId = null)
    {
    }

    public function payload(): array
    {
        $payments = Db::table(Installer::prefix() . 'cuican_pay_channel')
            ->where('status', 1)
            ->order('sort asc')
            ->field('code,name,status')
            ->select();
        if (!$payments) {
            // 插件表没配通道时，复用 macCMS「在线支付配置」里的 Epay
            $mac = MacPay::epayChannel();
            if ($mac !== null) {
                $payments = [['code' => $mac['code'], 'name' => $mac['name'], 'status' => 1]];
            }
        }

        return [
            // 皮肤与布局：1..5 对应 5 套皮肤
            'app_theme_ui' => Setting::get('app_theme_ui', '1'),
            'app_banner_style' => Setting::get('app_banner_style', '1'),
            'app_ui_tab_fun' => Setting::get('app_ui_tab_fun', '0'),
            // 这四个字段曾用于判断客户端完整性；现在只当作契约标记
            'app_cc_fd_jc' => Setting::get('app_cc_fd_jc', '1'),
            'ui_home_data' => Setting::get('ui_home_data', '3'),
            'ui_home_topbg' => Setting::get('ui_home_topbg', '0'),
            'app_ui_home_small_icons' => Setting::get('app_ui_home_small_icons', '1'),
            'app_home_ads' => Setting::get('app_home_ads', '0'),
            'vip_close_ad' => Setting::get('vip_close_ad', '0'),
            'app_play_recommend' => Setting::get('app_play_recommend', '1'),
            'app_user_adurl' => Setting::get('app_user_adurl', ''),
            // 只认 vip_purchase：会员关闭时一并下发 0，避免客户端还显示套餐区
            'vip_purchase' => Setting::int('member_enabled', 1) === 1
                ? Setting::get('vip_purchase', '1')
                : '0',
            // 会员体系总开关；关闭后客户端隐藏会员页与底栏入口，历史会员权益保留至到期
            'member_enabled' => Setting::get('member_enabled', '1'),
            // 强制登录：'1' 时客户端冷启动直接锁全屏登录，未登录进不了 App
            'force_login' => Setting::get('app_force_login', '0'),
            'online_recharge' => Setting::get('online_recharge', '1'),
            // 登录/注册方式：客户端只渲染勾选的方式，开关以后端为准
            'login_modes' => Setting::json('login_modes', ['device', 'password']),
            'register_modes' => Setting::json('register_modes', ['account']),
            'login_default' => Setting::get('login_default', 'device'),
            'device_recovery' => Setting::get('device_recovery', '1'),
            // 协议页面地址：客户端点《用户协议》《隐私政策》直接打开，留空时只提示未配置
            'agreement_url' => Setting::get('agreement_url', ''),
            'privacy_url' => Setting::get('privacy_url', ''),
            // 只认 app_login_type 的调用方：含设备登录时下发 "0"，
            // 否则 "1"。新客户端不读这个字段。
            'app_login_type' => in_array('device', Setting::json('login_modes', ['device', 'password']), true)
                ? '0'
                : '1',
            'search_hot' => Setting::json('search_hot', []),
            'home_modules' => Setting::json('home_modules', self::DEFAULT_MODULES),
            'banner_style' => Setting::get('banner_style', '1'),
            'banner_limit' => Setting::int('banner_limit', 6),
            // 短剧分类（mac_type.type_id）：后台可以填多个，`21,22,23` 逗号分隔。
            // shortplay_type_id 保留成第一个，部分调用方只读这一个；新版读下面的数组。
            'shortplay_type_id' => $this->shortplayTypeIds()[0] ?? 0,
            'shortplay_types' => $this->shortplayTypes(),
            'bottom_tabs' => $this->bottomTabs(),
            // 底栏是否显示图标：关闭后客户端只画文字，并按 tab 数量自动放大字号
            'bottom_tab_icons' => Setting::int('bottom_tab_icons', 1),
            'payments' => array_map(static fn (array $row): array => [
                'payment' => (string)$row['code'],
                'name' => (string)$row['name'],
                'status' => (int)$row['status'],
            ], $payments),
            // 广告位：5 个位各自下发 AdSlotDto（id/status/description）+ 直投资材，素材缺失时为空串
            'ads' => $this->maskAdsForVip([
                'ad_platform' => Setting::get('ad_platform', 'noop'),
                'sdk_appid' => Setting::get('ad_sdk_appid', ''),
                // 客户端读的是 application_id（AdSlotDto），与 sdk_appid 同值；缺了就是空广告位
                'application_id' => self::slot(Setting::get('ad_sdk_appid', '')),
                'sdk_startup' => $this->adSlot('splash'),
                'sdk_banner' => $this->adSlot('banner'),
                'sdk_informationflow' => $this->adSlot('feed'),
                'sdk_excitation' => $this->adSlot('reward'),
                'player_pause' => $this->adSlot('pause'),
                // 直投激励片源（上传优先，其次外链）：客户端要**直接播**它，空串 = 没配。
                // 与 sdk_excitation.status 配套：status=1 才显示入口，地址由这里拿。
                'ad_reward_video' => AdReward::videoUrl(),

                // 聚合平台参数：ad_platform 决定客户端用哪段初始化（当前只有 TradPlus）
                'tradplus' => [
                    'app_id' => Setting::get('tradplus_app_id', ''),
                    'splash' => Setting::get('tradplus_splash', ''),
                    'reward' => Setting::get('tradplus_reward', ''),
                    'feed' => Setting::get('tradplus_feed', ''),
                    'pause' => Setting::get('tradplus_pause', ''),
                    'banner' => Setting::get('tradplus_banner', ''),
                ],
                // 启动图 HTML：为空即不展示，客户端按 description 是否为空判定
                // 首启广告隐私弹窗正文：留空时客户端用本地默认文案（R.string.ad_consent_body）
                'ad_consent_text' => trim((string)Setting::get('ad_consent_text', '')),
                'startup_adv' => self::slot(Setting::get('ad_startup_html', '')),
                'xunfei_appid' => self::slot(Setting::get('xunfei_appid', '')),
            ], $this->userId),
            // 播放器广告：暂停图 / 角标 / 历史入口数据，后台「播放器广告」页维护
            // 详情页三个开关，后台「运营设置 → 详情页」维护
            'detail_sections' => DetailFlags::all(),
            // 推送条数：客户端按 >0 冒红点
            'push' => (new Push())->count(),
            // 0 = 客户端不展示推送入口；缺字段按展示处理
            'push_enabled' => Setting::get('push_enabled', '1'),
        ];
    }

    /**
     * VIP 不出广告：后台承诺「VIP 始终不出广告」，客户端不做会员判断，由这里统一压掉。
     *
     * 只把 5 个直投位的 `status` 置 0，`description` / `image` / `url` / `title` /
     * `interval` / `seconds` 原样下发（客户端切账号后不必重新拉包）。
     * startup 是公开接口：未登录时 `$userId` 为 null 或 0，直接原样返回，不查库。
     *
     * @param array<string,mixed> $ads
     * @return array<string,mixed>
     */
    private function maskAdsForVip(array $ads, ?int $userId): array
    {
        if ($userId === null || $userId <= 0) {
            return $ads;
        }
        // isVip 内部自己查一次用户表，所以只调用一次；不要放进下面的循环里
        if (!(new Playback())->isVip($userId)) {
            return $ads;
        }
        $slots = ['sdk_startup', 'sdk_banner', 'sdk_informationflow', 'sdk_excitation', 'player_pause'];
        foreach ($slots as $field) {
            if (isset($ads[$field]) && is_array($ads[$field])) {
                $ads[$field]['status'] = 0;
            }
        }
        return $ads;
    }

    /** 广告位通用壳：`description` 为空即视为关闭（客户端判定 `status==1 && description 非空`）。 */
    private static function slot(string $description): array
    {
        return ['id' => 0, 'status' => trim($description) !== '' ? 1 : 0, 'description' => $description];
    }

    /**
     * 该广告位是否已配置。
     *
     * 先看当前聚合平台对应的位 ID（`tradplus_` 前缀），没有再退回直投：
     * `ad_slot_{key}` 位 ID、`ad_{key}_image` 图片、激励位的直投视频，任一非空即算配置。
     *
     * 激励位为什么要多认一个视频：纯直投（后台只传了 mp4/webm）的站没有位 ID 也没有底图，
     * 少这一条 `sdk_excitation.status` 恒为 0，客户端连「看广告解锁」的入口都不渲染，
     * 而服务端其实是能签发会话、也有片源可播的。与 `Playback::rewardAdConfigured()` 同口径。
     */
    private function slotConfigured(string $key): bool
    {
        $platform = strtolower(trim(Setting::get('ad_platform', 'noop')));
        // 只认这一家；noop 或拼错的值不给任何前缀，直接走直投判定
        if ($platform === 'tradplus') {
            if (trim(Setting::get($platform . '_' . $key, '')) !== '') {
                return true;
            }
        }
        if ($key === 'reward' && AdReward::videoUrl() !== '') {
            return true;
        }
        return trim(Setting::get('ad_slot_' . $key, '')) !== ''
            || trim(Setting::get('ad_' . $key . '_image', '')) !== '';
    }

    /**
     * 单个广告位。`status==1` 且「平台位 ID / 直投位 ID / 图片 / 激励视频」至少一个非空才算开启，
     * 与客户端 AdSlotDto.isEnabled 的口径一致；总开关 ad_enabled=0 时一律关闭。
     *
     * @return array{id:int,status:int,description:string,image:string,url:string,title:string,interval:int,seconds:int}
     */
    private function adSlot(string $key): array
    {
        $slotId = Setting::get('ad_slot_' . $key, '');
        $enabled = Setting::int('ad_enabled', 0) === 1 && $this->slotConfigured($key);
        return [
            'id' => 0,
            'status' => $enabled ? 1 : 0,
            'description' => $slotId,
            // 上传存的是站内相对路径（upload/...），云存储则是完整 URL：
            // 统一经 Media::url 归一化，客户端只拿到能直接加载的绝对地址。
            'image' => Media::url((string)Setting::get('ad_' . $key . '_image', '')),
            'title' => Setting::get('ad_' . $key . '_title', ''),
            'subtitle' => Setting::get('ad_' . $key . '_subtitle', ''),
            // 信息流位才读插播间隔，其它位恒 0
            'interval' => $key === 'feed' ? max(0, Setting::int('ad_feed_interval', 0)) : 0,
            // 激励 1..30 秒；暂停允许 0（0 = 不限制，贴片一出现就能关）；其它位恒 0
            'seconds' => $this->slotSeconds($key),
        ];
    }

    /** 位的倒计时秒数：激励必须 ≥1（否则"看完即完成"），暂停允许 0（表示不限制）。 */
    private function slotSeconds(string $key): int
    {
        if ($key === 'splash') {
            // 开屏时长可配：1-10 秒，默认 5。上限压到 10 是因为开屏挡路，
            // 再长就是干扰；客户端拿到 0 时才回落到自己的默认值。
            return min(10, max(1, Setting::int('ad_splash_seconds', 5)));
        }
        if ($key === 'reward') {
            return min(30, max(1, Setting::int('ad_reward_seconds', 5)));
        }
        if ($key === 'pause') {
            return min(30, max(0, Setting::int('ad_pause_seconds', 5)));
        }
        if ($key === 'feed') {
            // 信息流强制停留：0 = 不限制（可以立刻滑走），上限 10 秒，再长就是劝退
            return min(10, max(0, Setting::int('ad_feed_seconds', 3)));
        }
        return 0;
    }

    /**
     * 底部菜单下发值。
     *
     * 会员体系关闭时把 `member` 项强制置为不可见，但**不改库里的 JSON**：
     * 重新开启会员后菜单原样恢复，运营不需要手动改回 `bottom_tabs`。
     *
     * @return array<int,mixed>
     */
    private function bottomTabs(): array
    {
        $tabs = Setting::json('bottom_tabs', self::DEFAULT_TABS);
        if (Setting::int('member_enabled', 1) === 1) {
            return $tabs;
        }
        foreach ($tabs as $index => $tab) {
            if (is_array($tab) && (string)($tab['id'] ?? '') === 'member') {
                $tabs[$index]['visible'] = false;
            }
        }
        return $tabs;
    }

    /**
     * 后台「短剧分类 id」原始值：支持 `21,22,23` 这种逗号串，去重且只留正数。
     *
     * @return array<int,int>
     */
    private function shortplayTypeIds(): array
    {
        $raw = (string)Setting::get('shortplay_type_id', '');
        $ids = [];
        foreach (explode(',', $raw) as $piece) {
            $id = (int)trim($piece);
            if ($id > 0 && !in_array($id, $ids, true)) {
                $ids[] = $id;
            }
        }

        return $ids;
    }

    /**
     * 短剧分类的 id + 名字，客户端顶部 chips 按这个顺序出。
     *
     * 表里查不到（分类被删）就跳过，不拿 id 拼假名字。
     *
     * @return array<int,array{type_id:int,type_name:string}>
     */
    private function shortplayTypes(): array
    {
        $ids = $this->shortplayTypeIds();
        if ($ids === []) {
            return [];
        }
        $names = Db::name('type')
            ->whereIn('type_id', $ids)
            ->column('type_name', 'type_id');
        $list = [];
        foreach ($ids as $id) {
            if (isset($names[$id])) {
                $list[] = ['type_id' => $id, 'type_name' => (string)$names[$id]];
            }
        }

        return $list;
    }
}
