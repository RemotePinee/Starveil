<?php
/**
 * 直投激励视频：服务端签发一次性会话 + 按进度判定解锁（`v1.ad/session|progress|complete`）。
 *
 * 为什么不能沿用聚合平台的 SSV：直投是运营自己配素材、客户端自己播，没有第三方服务器
 * 来签 `trans_id`，也就没有可信的"看完了"回执。这里把回执换成**服务端自己的计时**：
 * 签发会话时记 `start_at`，客户端只回报播放进度，服务端按「进度 ≤ 真实流逝时间 + 3 秒」
 * 判定这条进度不是编的，看满 `seconds` 秒才写解锁记录。
 * 一句话：客户端上报只是"进度证明"，任何"我已经看完了"的自证都不采信。
 *
 * 为什么和 `AdSsvController` 分开：那个类的每个动作都不登录（调用方是广告平台），
 * 的三个动作必须登录。混进同一个类里，早晚有人把 `requireLogin()` 带进回调，
 * 于是平台的服务器收到 401、客户端永远解不了锁。两者共用 `v1.ad` 这个前缀，
 * 由 `Kernel::ACTION_CONTROLLERS` 按动作分派。
 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Installer;
use Starveil\Api\Service\Playback;
use Starveil\Api\Support\AdReward;
use think\Db;

final class AdRewardController extends BaseController
{
    /**
     * 会话有效期（秒）。
     *
     * 只约束"从签发到完成"这一段：填表、加载片源、看 5-60 秒、再回报一次，
     * 15 分钟对慢网用户有余量；过期后既不收进度也不给解锁，避免一条 3 天前的会话
     * 攒着进度在任意时刻换钱（也顺手把表里不用的行判成死行）。
     */
    private const SESSION_TTL = 900;

    /**
     * 进度允许超过真实流逝时间的宽限（秒）。
     *
     * 客户端不可能比真实时间跑得快，但它可能在一次上报里带上"播放器已经缓冲到的位置"，
     * 再加上网络抖动导致的时钟差，所以留 3 秒。超过就**夹取而不是拒绝**：
     * 拒绝会把一次时钟漂移变成"这次广告白看"，而夹取后用户继续看、继续报，
     * 下一轮就追上了，体验上等于没发生。
     */
    private const POSITION_SLACK = 3;

    /**
     * 同一用户同一集复用会话的窗口（秒）。
     *
     * 选"返回旧 token"而不是"新建一个、把旧的置过期"：客户端重进广告页（旋转屏幕、
     * 退后台再回来、网络失败重试）都会再要一次会话，若新建，`start_at` 被重置，
     * 而播放器还在播上一个 token 对应的时间线——两个会话各记一半进度，谁都到不了
     * `seconds`，用户看完却解不了锁。复用还顺带挡掉"每秒钟要一次会话"的刷表。
     */
    private const REUSE_WINDOW = 60;

    /**
     * `POST v1.ad/session`：签发会话。
     *
     * 参数 `vod_id` `episode_number`；返回 `token` / `seconds` / `video_url`。
     * 片源与秒数都取后台配置（见 [AdReward]），客户端不能指定——能指定就能把
     * "看 5 秒"改成"看 0 秒"。
     */
    public function postSession(): void
    {
        $this->requireLogin();
        $userId = $this->userId();

        $vodId = $this->input->int('vod_id');
        $episode = $this->input->int('episode_number');
        if ($vodId <= 0 || $episode <= 0) {
            Json::fail(400, '参数不完整');
            return;
        }

        // 位没配好 / 总开关关着时不开会话：否则等于给"关掉广告"的站点留了一条免费解锁
        if (!(new Playback())->rewardAdConfigured()) {
            Json::fail(400, '激励广告位未配置');
            return;
        }
        $videoUrl = AdReward::videoUrl();
        if ($videoUrl === '') {
            // 只配了位 ID（聚合）或底图，没有可播的直投片源：直投链路无从播起
            Json::fail(400, '激励视频未配置');
            return;
        }

        $table = $this->table();
        $reused = $this->reusable($table, $userId, $vodId, $episode);
        if ($reused !== null) {
            // seconds 用会话里存的那个：服务端按它判定完成，客户端拿到的也必须是它，
            // 否则改了后台配置会让"看到的倒计时"和"实际要求"对不上
            Json::ok([
                'token' => (string)$reused['token'],
                'seconds' => (int)$reused['seconds'],
                'video_url' => $videoUrl,
            ]);
            return;
        }

        $now = time();
        $seconds = AdReward::videoSeconds();
        // 32 位十六进制 = random_bytes(16)：128 位熵，不可枚举，也不需要额外签名
        $token = bin2hex(random_bytes(16));
        try {
            Db::table($table)->insert([
                'token' => $token,
                'user_id' => $userId,
                'vod_id' => $vodId,
                'episode' => $episode,
                'seconds' => $seconds,
                'max_position' => 0,
                'start_at' => $now,
                'updated_at' => $now,
                'completed_at' => 0,
                'status' => 0,
            ]);
        } catch (\Throwable $e) {
            // 表不存在说明没跑 v2.5.8 迁移，这里给可执行的下一步；
            // 其它错误（连接、锁）照常抛给 Kernel 记日志
            if (str_contains($e->getMessage(), '1146')) {
                Json::fail(500, '广告会话表未建立：请打开一次插件后台或跑 tools/install.php');
                return;
            }
            throw $e;
        }

        Json::ok(['token' => $token, 'seconds' => $seconds, 'video_url' => $videoUrl]);
    }

    /**
     * `POST v1.ad/progress`：上报播放进度。
     *
     * 参数 `token` `position`（秒）。只写 `max_position`，不写解锁——
     * 解锁在 [postComplete] 里一次性完成，这里被抓包重放最多是多记几秒真实进度。
     */
    public function postProgress(): void
    {
        $this->requireLogin();
        $row = $this->load($this->userId(), $this->token());
        if ($row === null) {
            return; // load 已经回过失败 JSON
        }
        // 已完成的会话不再收进度：写进去也没用（下面的条件更新带 status=0），
        // 但明确回一句，客户端不必再等一个永远不会变化的 max_position
        if ((int)$row['status'] !== 0) {
            Json::fail(400, '会话已完成');
            return;
        }

        $now = time();
        $startAt = (int)$row['start_at'];
        $seconds = (int)$row['seconds'];
        if ($now - $startAt > self::SESSION_TTL) {
            Json::fail(400, '会话已过期，请重新观看');
            return;
        }

        // 先按片长夹：position 是"播放到第几秒"，不可能超过视频本身（+5 秒容缓冲尾巴）
        $position = min($seconds + 5, max(0, $this->input->int('position')));
        // 再按真实流逝时间夹：这是整套防伪的核心，客户端控制不了服务端的 now
        $elapsed = max(0, $now - $startAt);
        $capped = min($position, $elapsed + self::POSITION_SLACK);

        // 用条件更新保证只增不减：并发上报时，先落库的小值不会把后到的大值盖回去
        Db::table($this->table())
            ->where('token', (string)$row['token'])
            ->where('user_id', (int)$row['user_id'])
            ->where('status', 0)
            ->where('max_position', '<', $capped)
            ->update(['max_position' => $capped, 'updated_at' => $now]);

        // 返回值以"落库前读到的行 + 本次值"取大：条件更新命中与否都能给客户端一个单调的进度
        $max = max((int)$row['max_position'], $capped);
        Json::ok([
            'seconds' => $seconds,
            'max_position' => $max,
            'remaining' => max(0, $seconds - $max),
        ]);
    }

    /**
     * `POST v1.ad/complete`：结算并写解锁记录。
     *
     * 三个条件缺一不可：进度看满、真实流逝时间也看满、会话还没完成。
     * "进度满"单独不够——一个 `position=seconds` 的上报在会话刚开始时就会被进度防伪夹到
     * 几秒，所以这里再核一次流逝时间，防的是"进度被夹取后仍然满"的极端配置（seconds=1）。
     */
    public function postComplete(): void
    {
        $this->requireLogin();
        $row = $this->load($this->userId(), $this->token());
        if ($row === null) {
            return;
        }

        // 已完成：幂等回成功。客户端拿不到响应会重发，这时回 400 会让用户以为白看了
        if ((int)$row['status'] !== 0) {
            Json::ok(['unlocked' => 1], '已解锁');
            return;
        }

        $now = time();
        $startAt = (int)$row['start_at'];
        $seconds = (int)$row['seconds'];
        if ($now - $startAt > self::SESSION_TTL) {
            Json::fail(400, '会话已过期，请重新观看');
            return;
        }
        if ((int)$row['max_position'] < $seconds) {
            Json::fail(400, '观看进度不足（已看 ' . (int)$row['max_position'] . '/' . $seconds . ' 秒）');
            return;
        }
        // -1：客户端在 seconds-1 秒时点完成是常见时序，扣掉这一秒不算放水
        if ($now - $startAt < $seconds - 1) {
            Json::fail(400, '观看时长不足，请继续观看');
            return;
        }

        $table = $this->table();
        $unlockTable = Installer::prefix() . 'cuican_shortplay_unlock';
        Db::startTrans();
        try {
            // 条件更新就是并发锁：两个请求同时结算只有一个 affected=1，
            // 另一个走下面的幂等分支，解锁记录不会写两行
            $affected = Db::table($table)
                ->where('token', (string)$row['token'])
                ->where('user_id', (int)$row['user_id'])
                ->where('status', 0)
                ->update(['status' => 1, 'completed_at' => $now, 'updated_at' => $now]);
            if ((int)$affected === 0) {
                Db::commit();
                Json::ok(['unlocked' => 1], '已解锁');
                return;
            }
            $this->recordUnlock(
                $unlockTable,
                (int)$row['user_id'],
                (int)$row['vod_id'],
                (int)$row['episode'],
                (string)$row['token']
            );
            Db::commit();
        } catch (\Throwable $e) {
            // 解锁写不进去就整体回滚：会话仍是 status=0，用户可以再点一次完成
            Db::rollback();
            throw $e;
        }

        Json::ok(['unlocked' => 1], '解锁成功');
    }

    /**
     * 写解锁记录。字段与 `AdSsvController::recordUnlock()` 逐字一致，只把
     * `unlock_type` 换成 `direct`、`ad_trans_id` 用会话 token：
     * 后台按 `unlock_type` 能分清"聚合回调解锁"与"直投看完解锁"，两类都靠 `ad_trans_id`
     * 唯一索引兜住重复写入。
     */
    private function recordUnlock(
        string $table,
        int $userId,
        int $vodId,
        int $episode,
        string $token
    ): void {
        try {
            Db::table($table)->insert([
                'user_id' => $userId,
                'vod_id' => $vodId,
                'episode_number' => $episode,
                'unlock_type' => 'direct',
                'ad_trans_id' => $token,
                'created_at' => time(),
            ]);
        } catch (\Throwable $e) {
            // 唯一索引命中 = 这个 token 的解锁记录之前就写成了（重试/并发），与写成功等价；
            // 其它错误（连接、表缺失）照常抛出，交给 [postComplete] 回滚事务
            if (!str_contains($e->getMessage(), '1062') && !str_contains($e->getMessage(), 'Duplicate')) {
                throw $e;
            }
        }
    }

    /**
     * 读会话：必须属于当前用户。
     *
     * 不属于自己与不存在回同一个提示，不给调用方探测"这个 token 是不是别人的"的机会。
     *
     * @return array<string,mixed>|null 失败时已回 JSON
     */
    private function load(int $userId, string $token): ?array
    {
        if ($token === '') {
            Json::fail(400, '参数不完整');
            return null;
        }
        try {
            $row = Db::table($this->table())
                ->where('token', $token)
                ->where('user_id', $userId)
                ->find();
        } catch (\Throwable $e) {
            if (str_contains($e->getMessage(), '1146')) {
                Json::fail(500, '广告会话表未建立：请打开一次插件后台或跑 tools/install.php');
                return null;
            }
            throw $e;
        }
        if (!is_array($row)) {
            Json::fail(400, '会话不存在或已失效');
            return null;
        }
        return $row;
    }

    /**
     * 60 秒内同一用户同一集还没完成的会话。
     *
     * 查询失败（表缺失）按"没有可复用"处理，让 [postSession] 的插入分支给出明确提示。
     *
     * @return array<string,mixed>|null
     */
    private function reusable(string $table, int $userId, int $vodId, int $episode): ?array
    {
        try {
            $row = Db::table($table)
                ->where('user_id', $userId)
                ->where('vod_id', $vodId)
                ->where('episode', $episode)
                ->where('status', 0)
                ->where('start_at', '>=', time() - self::REUSE_WINDOW)
                ->order('start_at desc')
                ->find();
        } catch (\Throwable $e) {
            // 表还没建（没跑 v2.5.8 迁移）时按"没有可复用的会话"处理：
            // 插入分支会撞同一个错误并给出可执行的提示，这里不必再报一次
            return null;
        }
        return is_array($row) ? $row : null;
    }

    /**
     * token 入参：只收 32 位十六进制。
     *
     * 形状不对就当没传，避免把一个 64 字符的任意串带进查询（列宽是 64，超长值在
     * MySQL 严格模式下会报 1406，非严格模式下会被截断后去匹配别人的 token）。
     */
    private function token(): string
    {
        $token = $this->input->str('token');
        return preg_match('/^[0-9a-f]{32}$/', $token) === 1 ? $token : '';
    }

    private function table(): string
    {
        return Installer::prefix() . 'cuican_ad_session';
    }
}
