<?php
/**
 * 弹幕（`v1.danmu/index`、`v1.danmu`），读写 macCMS 自己的 `mac_danmaku`。
 *
 * 不用插件的 `cuican_danmu`：弹幕要和网页播放器共用一份数据，
 * 否则 App 发出去的弹幕在 PC 站永远看不到、反之亦然。
 *
 * 列名依据 macCMS 10 自带结构 `application/install/sql/install.sql` 的 `mac_danmaku`：
 *   danmaku_id / vod_id / vod_sid / vod_nid / user_id / user_name /
 *   danmaku_time（float，弹幕出现的时间点，秒）/ danmaku_type / danmaku_color /
 *   danmaku_text / danmaku_ip / danmaku_send_time / danmaku_status / danmaku_report
 * 线上站点若被二次开发改过列名，上线前用 `DESCRIBE <前缀>danmaku` 核对；
 * 本类只读写上面这些列，没有依赖其它字段。
 *
 * 契约：拉取按时间正序、`limit` 最多 200；发送成功返回 `{"score":0}`，
 * 积分发放走既有逻辑，这里不发分。
 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Installer;
use think\Db;

final class DanmuController extends BaseController
{
    /** `danmaku_color` varchar(10)，只接受 #RRGGBB；这是默认色。 */
    private const DEFAULT_COLOR = '#FF7F00';

    /** `danmaku_text` varchar(200)。 */
    /** `danmaku_text` varchar(200)。 */
    private const MAX_CONTENT = 200;

    /**
     * 同一用户两次发弹幕的最小间隔（秒）。
     *
     * 评论 30 秒、留言 60 秒都有冷却，弹幕原来没有：一个登录账号可以循环 POST
     * 无限刷屏并无限写 `mac_danmaku`。弹幕本身是高频互动，10 秒足够宽松。
     */
    private const COOLDOWN = 10;

    /** 一次最多拉 200 条，避免整集弹幕把启动带宽打满。 */
    private const MAX_LIMIT = 200;

    public function getIndex(): void
    {
        $vodId = $this->input->int('vod_id');
        if ($vodId <= 0) {
            Json::fail(400, '参数不完整');
            return;
        }

        $limit = min(max(1, $this->input->int('limit', 100)), self::MAX_LIMIT);
        // start 是行偏移（`limit=100&start=0` 分页）；
        // at_time 是增量游标：只取这个时间点之后的弹幕，用于播放中继续拉。
        $start = max(0, $this->input->int('start'));
        $atTime = (int)floor((float)$this->input->str('at_time', '0'));

        $query = Db::table(self::table())
            ->where('vod_id', $vodId)
            ->where('danmaku_status', 1);
        if ($atTime > 0) {
            $query->where('danmaku_time', '>=', $atTime);
        }

        $rows = $query
            ->field('danmaku_id,danmaku_text,danmaku_color,danmaku_time')
            ->order('danmaku_time asc,danmaku_id asc')
            ->limit($start, $limit)
            ->select();

        $list = [];
        foreach ($rows as $row) {
            $list[] = [
                'id' => (int)$row['danmaku_id'],
                'content' => (string)$row['danmaku_text'],
                'color' => (string)$row['danmaku_color'],
                // 客户端按整秒定位弹幕（danmaku_time 是 float，向下取整不会让弹幕提前出现）
                'at_time' => (int)floor((float)$row['danmaku_time']),
            ];
        }
        Json::ok(['list' => $list]);
    }

    public function postIndex(): void
    {
        $user = $this->requireLogin();

        $vodId = $this->input->int('vod_id');
        $content = mb_substr($this->input->str('content'), 0, self::MAX_CONTENT);
        if ($vodId <= 0 || $content === '') {
            Json::fail(400, '参数不完整');
            return;
        }

        $color = strtoupper($this->input->str('color', self::DEFAULT_COLOR));
        if (preg_match('/^#[0-9A-F]{6}$/', $color) !== 1) {
            // 客户端可以传任意字符串，非 #RRGGBB 一律回落默认色，不把脏值写进库
            $color = self::DEFAULT_COLOR;
        }

        // 冷却：同一用户 10 秒内只能发一条。评论 30 秒、留言 60 秒都有，
        // 弹幕原来没有，登录脚本循环 POST 就能无限刷屏并无限写表。
        $recent = Db::table(self::table())
            ->where('user_id', (int)$user['user_id'])
            ->where('danmaku_time', '>', time() - self::COOLDOWN)
            ->find();
        if ($recent) {
            Json::fail(429, '发送太频繁，请稍后再试');
            return;
        }

        Db::table(self::table())->insert([
            'vod_id' => $vodId,
            // App 的老契约不带播放源/集数，统一落在 0/0；网页播放器按自己的 sid/nid 取数互不干扰
            'vod_sid' => 0,
            'vod_nid' => 0,
            'user_id' => (int)$user['user_id'],
            'user_name' => mb_substr($this->authorName($user), 0, 30),
            'danmaku_time' => max(0, (int)floor((float)$this->input->str('at_time', '0'))),
            'danmaku_type' => 0,
            'danmaku_color' => $color,
            'danmaku_text' => $content,
            'danmaku_ip' => self::ipLong(),
            'danmaku_send_time' => time(),
            'danmaku_status' => 1,
            'danmaku_report' => 0,
        ]);

        // 积分由既有发分逻辑处理（VodController 的观看/评分链路），这里只回契约里的字段
        Json::ok(['score' => 0], '弹幕已发送');
    }

    /** macCMS 的弹幕表，不带插件前缀：App 与网页播放器共用同一份数据。 */
    private static function table(): string
    {
        return Installer::prefix() . 'danmaku';
    }

    /** 登录用户的落款：昵称，没有昵称用账号（与评论一致）。 */
    private function authorName(array $user): string
    {
        $nick = trim((string)($user['user_nick_name'] ?? ''));
        $name = $nick !== '' ? $nick : trim((string)($user['user_name'] ?? ''));
        return $name !== '' ? $name : '匿名';
    }

    /** `danmaku_ip` 是 int unsigned，按无符号写（与 comment_ip 一致）。 */
    private static function ipLong(): int
    {
        $long = ip2long((string)($_SERVER['REMOTE_ADDR'] ?? ''));
        return $long === false ? 0 : (int)sprintf('%u', $long);
    }
}
