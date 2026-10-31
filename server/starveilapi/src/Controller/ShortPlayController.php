<?php
/**
 * 短剧广告解锁（SSV）。
 *
 * 客户端看完激励视频后来这里换播放地址。校验**优先看服务端自己的解锁记录**
 * （平台 SSV 回调写入）；客户端自签是历史兼容路径，**默认关闭**（见 [LEGACY_SIGN_SETTING]），
 * 现行客户端已没有任何 `shortplay/unlock` 调用。客户端不缓存未解锁集的可播地址。
 * 规则见 `docs/shortplay-ads-plan.md` §5 P4 / §6.4。
 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Installer;
use Starveil\Api\Repo\Vods;
use Starveil\Api\Support\Setting;
use think\Db;

final class ShortPlayController extends BaseController
{
    /** 允许的时钟偏差：与广告平台回调的 timestamp 相差超过 10 分钟视为过期。 */
    private const SIGN_WINDOW_SECONDS = 600;

    /**
     * 客户端自签路径的开关（`shortplay_legacy_client_sign`），**默认 0 = 关闭**。
     *
     * 【风险】开启后客户端可自行签名解锁：`shortplay_sign_secret` 必须编进老版 APK，
     * 而客户端又自己算签名 —— 反编译拿到密钥就能给任意 `vod_id` / `episode_number` 自签，
     * **完全不经过 `Playback::trySee`**。密钥一旦泄露，等于整站没有付费墙。
     *
     * 所以这里三件事一起做：默认关闭；密钥与广告 SSV 分开（[LEGACY_SIGN_SECRET_SETTING]），
     * 免得平台侧泄露直接变成全站解锁；注释标明这是**历史兼容路径，仅为老版本保留**。
     * 现行客户端已不再自签（`app` / `feature` 里没有任何调用），确认线上没有老版本后
     * 把这条路径连同 [signValid] 一起删掉。
     */
    private const LEGACY_SIGN_SETTING = 'shortplay_legacy_client_sign';

    /**
     * 自签专用密钥（`shortplay_sign_secret`），与 `ad_ssv_secret` **分开**。
     *
     * 为什么不复用广告 SSV 那把：SSV 密钥要交给广告平台，本来就要流经第三方；
     * 自签再共用同一把，平台侧一旦泄露就直接等于全站解锁。两把各自独立，泄露面才不叠加。
     */
    private const LEGACY_SIGN_SECRET_SETTING = 'shortplay_sign_secret';

    /**
     * `POST v1.shortplay/unlock`。
     *
     * 参数：`vod_id` `episode_number`；旧客户端另带 `trans_id` `sign` `timestamp`。
     * `user_id` 不再信任请求体，一律取登录会话（未登录 401）。
     *
     * 两条路径，顺序不能换：
     * 1. **服务端记录优先**：平台 SSV 回调（[AdSsvController]）或直投看完
     *    （`AdRewardController`）已经把这一集写进解锁表，那就直接给地址，客户端不签名；
     * 2. **客户端自签（历史兼容，默认关闭）**：见 [LEGACY_SIGN_SETTING]。
     *    `shortplay_legacy_client_sign` 不为 `1` 时这里直接 403。
     */
    public function postUnlock(): void
    {
        $this->requireLogin();

        $vodId = $this->input->int('vod_id');
        $episode = $this->input->int('episode_number');
        if ($vodId <= 0 || $episode <= 0) {
            Json::fail(400, '参数不完整');
            return;
        }

        $userId = $this->userId();
        if ($this->unlockedByServer($userId, $vodId, $episode)) {
            $url = $this->episodeUrl($vodId, $episode);
            if ($url === '') {
                Json::fail(404, '剧集不存在');
                return;
            }
            Json::ok(['url' => $url, 'unlocked' => false], '该集已解锁');
            return;
        }

        if (Setting::get(self::LEGACY_SIGN_SETTING, '0') !== '1') {
            Json::fail(403, '请通过广告平台回调解锁');
            return;
        }

        $transId = $this->input->str('trans_id');
        $timestamp = $this->input->int('timestamp');
        // trans_id 对应 varchar(64)：先挡住超长值，避免走到 MySQL 的 1406
        if ($transId === '' || strlen($transId) > 64 || $timestamp <= 0) {
            Json::fail(400, '参数不完整');
            return;
        }

        // 自签只认专用密钥：与广告 SSV（ad_ssv_secret）分开，平台侧泄露不会变成全站解锁
        $secret = Setting::get(self::LEGACY_SIGN_SECRET_SETTING);
        if ($secret === '') {
            // 没有密钥就无法验签，宁可拒绝也不放行
            Json::fail(403, '短剧自签密钥未配置');
            return;
        }
        if (!$this->signValid($secret, $transId, $vodId, $episode, $timestamp)) {
            Json::fail(403, '签名校验失败');
            return;
        }

        $url = $this->episodeUrl($vodId, $episode);
        if ($url === '') {
            Json::fail(404, '剧集不存在');
            return;
        }

        $first = $this->recordUnlock($userId, $vodId, $episode, $transId);
        // 走到这里说明客户端参与了自签（这条路默认关闭，只有运营手工开启才会到达）。
        // 留一条日志：调用量降到 0 就说明线上没有老版本了，可以把整段删掉。
        error_log('[starveil] 短剧解锁走了客户端自签路径（待废弃） uid=' . $userId . ' vod=' . $vodId . ' ep=' . $episode);
        Json::ok(
            ['url' => $url, 'unlocked' => $first],
            $first ? 'success' : '该广告订单已解锁'
        );
    }

    /**
     * 旧客户端自签验签：`md5(trans_id|vod_id|episode_number|timestamp|shortplay_sign_secret)`。
     *
     * **历史兼容路径**：密钥在 APK 里，签名只等于"客户端声称自己看过广告"，不构成证明。
     * 平台 SSV 回调（[AdSsvController]）的原料已经带上 `user_id`，两边口径不同，
     * 不要互相抄。`timestamp` 用秒，窗口 ±600 秒，只能挡旧回调重放。
     */
    private function signValid(
        string $secret,
        string $transId,
        int $vodId,
        int $episode,
        int $timestamp
    ): bool {
        if (abs(time() - $timestamp) > self::SIGN_WINDOW_SECONDS) {
            return false;
        }
        $raw = $transId . '|' . $vodId . '|' . $episode . '|' . $timestamp . '|' . $secret;
        $sign = strtolower($this->input->str('sign'));
        return $sign !== '' && hash_equals(md5($raw), $sign);
    }

    /**
     * 服务端是否已经有这一集的解锁记录。
     *
     * `cuican_shortplay_unlock` 由平台 SSV 回调与直投看完两条路写入；这里只读。
     * 查询失败（插件还没跑 v2.2.0 迁移）一律当"未解锁"：不能让一个可选能力把接口打成 500。
     */
    private function unlockedByServer(int $userId, int $vodId, int $episode): bool
    {
        if ($userId <= 0) {
            return false;
        }
        try {
            return (bool)Db::table(Installer::prefix() . 'cuican_shortplay_unlock')
                ->where('user_id', $userId)
                ->where('vod_id', $vodId)
                ->where('episode_number', $episode)
                ->find();
        } catch (\Throwable $e) {
            return false;
        }
    }

    /**
     * 写解锁记录；`ad_trans_id` 唯一索引兜底并发重复回调。
     *
     * @return bool true = 本次新写入，false = 该 trans_id 已存在（幂等）
     */
    private function recordUnlock(int $userId, int $vodId, int $episode, string $transId): bool
    {
        $table = Installer::prefix() . 'cuican_shortplay_unlock';
        if (Db::table($table)->where('ad_trans_id', $transId)->find()) {
            return false;
        }
        try {
            Db::table($table)->insert([
                'user_id' => $userId,
                'vod_id' => $vodId,
                'episode_number' => $episode,
                'unlock_type' => 'ad',
                'ad_trans_id' => $transId,
                'created_at' => time(),
            ]);
            return true;
        } catch (\Throwable $e) {
            // 两个回调同时到达时唯一索引会先命中；按已解锁处理，不回 500
            if (str_contains($e->getMessage(), '1062') || str_contains($e->getMessage(), 'Duplicate')) {
                return false;
            }
            throw $e;
        }
    }

    /**
     * 取第 N 集播放地址。
     *
     * 解析与分发都交给 `Repo\Vods::playUrl()`：`vod_play_url` 的三层拆分（`$$$` / `#` / `$`）
     * 只有那一份实现，CDN 签名与「只在 CDN 自己域名下才改写地址」的判定也在那里。
     * 这里曾经自己签名，采集来的第三方直链被拼到 `cdn_domain` 上——CDN 上没有那份文件，
     * 解锁后拿到的地址全部 404，短剧看起来像"解锁了也播不了"。
     *
     * 两个调用方（[postUnlock] 的两条放行路径）都已解锁过，所以按「已解锁」口径取地址：
     * `Vods::playUrl()` 内部走 free + exper = 0（不截断），CDN 接通时给签名地址。
     */
    private function episodeUrl(int $vodId, int $episode): string
    {
        return (new Vods())->playUrl($vodId, 0, $episode);
    }
}
