<?php
/**
 * 短剧广告解锁（SSV）。
 *
 * 客户端看完激励视频后，拿广告平台回调里的 `trans_id` 来这里换播放地址；
 * 地址只在服务端校验签名后返回，客户端不缓存未解锁集的可播地址。
 * 规则见 `docs/shortplay-ads-plan.md` §5 P4 / §6.4。
 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Installer;
use Starveil\Api\Support\Setting;
use think\Db;

final class ShortPlayController extends BaseController
{
    /** 允许的时钟偏差：与广告平台回调的 timestamp 相差超过 10 分钟视为过期。 */
    private const SIGN_WINDOW_SECONDS = 600;

    /**
     * `POST v1.shortplay/unlock`。
     *
     * 参数：`vod_id` `episode_number` `trans_id` `sign` `timestamp`；
     * `user_id` 不再信任请求体，一律取登录会话（未登录 401）。
     */
    public function postUnlock(): void
    {
        $this->requireLogin();

        $vodId = $this->input->int('vod_id');
        $episode = $this->input->int('episode_number');
        $transId = $this->input->str('trans_id');
        $timestamp = $this->input->int('timestamp');
        // trans_id 对应 varchar(64)：先挡住超长值，避免走到 MySQL 的 1406
        if ($vodId <= 0 || $episode <= 0 || $transId === '' || strlen($transId) > 64 || $timestamp <= 0) {
            Json::fail(400, '参数不完整');
            return;
        }

        $secret = Setting::get('ad_ssv_secret');
        if ($secret === '') {
            // 没有密钥就无法验签，宁可拒绝也不放行
            Json::fail(403, '广告 SSV 密钥未配置');
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

        $first = $this->recordUnlock($this->userId(), $vodId, $episode, $transId);
        Json::ok(
            ['url' => $url, 'unlocked' => $first],
            $first ? 'success' : '该广告订单已解锁'
        );
    }

    /**
     * 验签：`md5(trans_id|vod_id|episode_number|timestamp|ad_ssv_secret)`。
     *
     * `timestamp` 用秒；窗口 ±600 秒，防止旧回调被重放。
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
     * `vod_play_url` 多源用 `$$$` 分隔，每源内 `#` 分集、`$` 分名称与地址；
     * 与 `Repo\Vods::playSources()` 口径一致，取第一个源。
     */
    private function episodeUrl(int $vodId, int $episode): string
    {
        $row = Db::name('vod')->where('vod_id', $vodId)->field('vod_id,vod_play_url')->find();
        if (!$row) {
            return '';
        }
        $sources = array_values(array_filter(
            explode('$$$', (string)($row['vod_play_url'] ?? '')),
            static fn (string $item): bool => $item !== ''
        ));
        $items = array_values(array_filter(
            explode('#', (string)($sources[0] ?? '')),
            static fn (string $item): bool => $item !== ''
        ));
        $item = (string)($items[$episode - 1] ?? '');
        if ($item === '') {
            return '';
        }
        $parts = explode('$', $item, 2);
        return trim((string)($parts[1] ?? ''));
    }
}
