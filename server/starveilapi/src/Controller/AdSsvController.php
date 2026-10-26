<?php
/**
 * 广告平台激励视频的 SSV（服务端校验）回调：`GET v1.ad/ssv`。
 *
 * 为什么单独一个控制器，而不是并进 `ShortPlayController`：
 * 1. 这是插件里**唯一不需要登录**的写接口（调用方是广告平台的服务器，没有 cookie），
 *    和 `v1.shortplay/unlock` 的 `requireLogin()` 放在一起，早晚有人把登录闸门带过来；
 * 2. 它的响应必须给平台看：`{"isValid":true,"code":0,"message":"ok"}` 且失败要真实 403，
 *    而插件统一的 `Json` 壳是 `{code,msg,data}`、HTTP 恒 200，两者不能共用。
 *
 * 为什么闭环只能这么走：`trans_id` 是平台在自己回调里生成的，客户端根本拿不到，
 * 所以客户端只能"告诉平台这次是谁"，由平台回调本端点写解锁记录，
 * 客户端再重查 `checkVodTrySee` / 短剧闸门时服务端读记录放行。
 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Installer;
use Starveil\Api\Support\Setting;
use think\Db;

final class AdSsvController extends BaseController
{
    /**
     * 回调时间窗 ±300 秒。
     *
     * `ad_trans_id` 唯一索引只能挡住"同一个 trans_id 重复到达"；一条旧回调被改一个
     * trans_id 就又是新记录，所以真正防重放的是时间窗。不能放宽到平台重试的
     * 3 次 × 2 秒之外太多，否则抓包攒下的回调可以反复换 id 刷解锁。
     */
    private const SIGN_WINDOW_SECONDS = 300;

    /**
     * 平台回调入口。
     *
     * 两家字段不同：TopOn 回 `user_custom_data`，TradPlus 回 `custom_data`，
     * 其余（`trans_id` / `user_id` / `timestamp` / `sign`）同名，所以都在这里兼容。
     */
    public function getSsv(): void
    {
        $transId = $this->input->str('trans_id');
        $userId = $this->input->int('user_id');
        $timestamp = $this->input->int('timestamp');
        [$vodId, $episode] = $this->target();

        $secret = Setting::get('ad_ssv_secret');
        if ($secret === '') {
            // 空密钥意味着"任何签名都算对"，比不验签更危险，直接拒
            $this->respond(false, 1001, 'ssv secret not configured', 403);
            return;
        }
        if ($transId === '' || strlen($transId) > 64 || $userId <= 0 || $vodId <= 0 || $episode <= 0) {
            $this->respond(false, 1002, 'invalid params', 400);
            return;
        }
        if (!$this->signValid($secret, $transId, $vodId, $episode, $timestamp)) {
            $this->respond(false, 1003, 'sign invalid', 403);
            return;
        }

        // 幂等：同一个 trans_id 再来一次也回 200，平台据此停止重试
        $this->recordUnlock($userId, $vodId, $episode, $transId);
        $this->respond(true, 0, 'ok', 200);
    }

    /**
     * 少数平台的回调走 POST：方法与 GET 完全同构，只做转发，避免回 404 让平台白重试。
     */
    public function postSsv(): void
    {
        $this->getSsv();
    }

    /**
     * 取这次回调要解锁的「剧 + 集」。
     *
     * 平台不知道我们的业务参数，它只会把客户端请求广告时塞进去的 custom_data 原样回传；
     * 所以优先取显式的 `vod_id` / `episode_number`（自测或二次封装回调会带），
     * 缺了再从 `user_custom_data`（TopOn）/ `custom_data`（TradPlus）里的
     * `vodId:episodeNumber` 兜底。
     *
     * @return array{0:int,1:int}
     */
    private function target(): array
    {
        $vodId = $this->input->int('vod_id');
        $episode = $this->input->int('episode_number');
        if ($vodId > 0 && $episode > 0) {
            return [$vodId, $episode];
        }
        $raw = $this->input->str('user_custom_data', $this->input->str('custom_data'));
        $parts = explode(':', $raw, 2);
        return [(int)($parts[0] ?? 0), (int)($parts[1] ?? 0)];
    }

    /**
     * 验签：`md5(trans_id|vod_id|episode_number|timestamp|ad_ssv_secret)`。
     *
     * `timestamp` 用秒。与 `v1.shortplay/unlock` 同一口径，两处必须一起改。
     */
    private function signValid(
        string $secret,
        string $transId,
        int $vodId,
        int $episode,
        int $timestamp
    ): bool {
        if ($timestamp <= 0 || abs(time() - $timestamp) > self::SIGN_WINDOW_SECONDS) {
            return false;
        }
        $sign = strtolower($this->input->str('sign'));
        $raw = $transId . '|' . $vodId . '|' . $episode . '|' . $timestamp . '|' . $secret;
        return $sign !== '' && hash_equals(md5($raw), $sign);
    }

    /**
     * 写解锁记录；`ad_trans_id` 唯一索引兜底并发重复回调。
     *
     * @return bool true = 本次新写入，false = 已存在或写不进去
     */
    private function recordUnlock(int $userId, int $vodId, int $episode, string $transId): bool
    {
        $table = Installer::prefix() . 'cuican_shortplay_unlock';
        try {
            if (Db::table($table)->where('ad_trans_id', $transId)->find()) {
                return false;
            }
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
            // 两种情况都按"已处理"回 200：并发第二次回调撞唯一索引（1062），
            // 或插件还没跑 v2.2.0 迁移、表不存在。这里给 500 只会让平台重试三次后放弃，
            // 用户这次广告白看；表缺失是运营可见的事（后台解锁记录里没有新行），不该由平台承担。
            return false;
        }
    }

    /**
     * 给平台的响应。
     *
     * 字段名沿用 TradPlus 的 SSV 约定（`isValid` / `code` / `message`）：它要求 2 秒内
     * 收到 `isValid=true` 才发奖，TopOn 同样以 HTTP 200 为发放依据，一家一份形状即可。
     */
    private function respond(bool $valid, int $code, string $message, int $httpStatus): void
    {
        if (!headers_sent()) {
            http_response_code($httpStatus);
            header('Content-Type: application/json; charset=utf-8');
        }
        echo json_encode(
            ['isValid' => $valid, 'code' => $code, 'message' => $message],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES
        );
    }
}
