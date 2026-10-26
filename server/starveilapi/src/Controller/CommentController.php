<?php
/**
 * 评论（`v1.comment`），读写 macCMS 自己的 `mac_comment`。
 *
 * 不再使用插件私有的 `cuican_comment`：App 发的评论要能在 PC 站评论区看到，
 * PC 站的评论也要能出现在 App 里，只有共用同一张表才成立。
 * 字段按 macCMS 的约定：`comment_mid`（1 = 影片）、`comment_rid`（= vod_id）、
 * `comment_status`（1 = 审核通过可见）、`comment_content`。
 *
 * 旧表 `cuican_comment` 保留只读，不再写入（老数据不做迁移，避免和 macCMS 的审核流程打架）。
 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Installer;
use think\Db;
use think\db\Query;

final class CommentController extends BaseController
{
    /** macCMS 的评论模块 id：1 = 影片（2 = 文章，3 = 专题）。 */
    private const MID_VOD = 1;

    /** `comment_content` 是 varchar(255)，但 PC 站评论区一行放不下，App 侧按 200 字截。 */
    private const MAX_CONTENT = 200;

    /** 同一用户两次发评论的最小间隔（秒）。 */
    private const COOLDOWN = 30;

    public function getIndex(): void
    {
        $vodId = $this->input->int('vod_id');
        $page = max(1, $this->input->int('page', 1));
        $limit = min(max(1, $this->input->int('limit', 20)), 60);
        // TP5.0.24 有两个坑，都用"另建 query"绕开（clone 也不行，见下）：
        // 1. count() 会改自身 options：field 变 COUNT(*)、limit 变 1、table 变聚合子查询，
        //    在同一对象上接着 select 会报 1146（提示表 `mac_` 不存在）；
        // 2. clone 出来的 Query 生成的是命名占位符（`:where_AND_comment_rid`）而 bind 为空，
        //    PDO 直接 HY093（占位符数量与 bind 对不上，clone 修不掉）。
        // 所以统计和取数各建一个 Query，条件只写在 queryFor() 一处。
        $total = (int)$this->queryFor($vodId)->count();
        $rows = $this->queryFor($vodId)->order('comment_id desc')->page($page, $limit)->select();

        $users = $this->usersOf($rows);
        $list = [];
        foreach ($rows as $row) {
            $user = $users[(int)$row['user_id']] ?? [];
            $list[] = [
                'comment_id' => (int)$row['comment_id'],
                'content' => (string)$row['comment_content'],
                'user_nick_name' => $this->displayName($row, $user),
                'user_portrait' => (string)($user['user_portrait'] ?? ''),
                'created_at' => (int)$row['comment_time'],
            ];
        }
        Json::ok(['total' => $total, 'page' => $page, 'limit' => $limit, 'list' => $list]);
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

        $table = self::table();
        $userId = (int)$user['user_id'];
        $recent = Db::table($table)
            ->where('user_id', $userId)
            ->where('comment_time', '>', time() - self::COOLDOWN)
            ->find();
        if ($recent) {
            Json::fail(429, '评论太频繁，请稍后再试');
            return;
        }

        Db::table($table)->insert([
            'comment_mid' => self::MID_VOD,
            'comment_rid' => $vodId,
            'comment_pid' => 0,
            'user_id' => $userId,
            'comment_status' => 1,
            'comment_name' => $this->authorName($user),
            'comment_ip' => self::ipLong(),
            'comment_time' => time(),
            'comment_content' => $content,
        ]);
        Json::ok(null, '评论已发布');
    }

    /** macCMS 的评论表，不带插件前缀：App 与 PC 站共用同一份数据。 */
    private static function table(): string
    {
        return Installer::prefix() . 'comment';
    }

    /**
     * 列表的取数条件只写一处：统计和取数各自建一个 Query（原因见 getIndex 的注释）。
     */
    private function queryFor(int $vodId): Query
    {
        return Db::table(self::table())
            ->where('comment_mid', self::MID_VOD)
            ->where('comment_rid', $vodId)
            ->where('comment_status', 1);
    }

    /**
     * 昵称与头像：批量查一次 mac_user，别在列表里逐行查（N+1）。
     *
     * @param array<int,array<string,mixed>> $rows
     * @return array<int,array<string,mixed>> user_id => row
     */
    private function usersOf(array $rows): array
    {
        $ids = [];
        foreach ($rows as $row) {
            $id = (int)$row['user_id'];
            if ($id > 0) {
                $ids[$id] = $id;
            }
        }
        if ($ids === []) {
            return [];
        }
        $users = [];
        $fields = 'user_id,user_nick_name,user_portrait';
        $userRows = Db::name('user')->whereIn('user_id', array_values($ids))->field($fields)->select();
        foreach ($userRows as $user) {
            $users[(int)$user['user_id']] = $user;
        }
        return $users;
    }

    /**
     * 展示名：优先 mac_user 的昵称，其次评论行里的 comment_name。
     *
     * 走 comment_name 的是游客评论（user_id=0）、账号已注销、以及 PC 站老数据。
     *
     * @param array<string,mixed> $row
     * @param array<string,mixed> $user
     */
    private function displayName(array $row, array $user): string
    {
        $name = $this->authorName($user);
        if ($name === '') {
            $name = trim((string)($row['comment_name'] ?? ''));
        }
        return $name !== '' ? $name : '游客';
    }

    /** 登录用户的落款：昵称，没有昵称用账号。 */
    private function authorName(array $user): string
    {
        $nick = trim((string)($user['user_nick_name'] ?? ''));
        return $nick !== '' ? $nick : trim((string)($user['user_name'] ?? ''));
    }

    /** `comment_ip` 是 int unsigned，按无符号写（与 mac_user 的 user_reg_ip 一致）。 */
    private static function ipLong(): int
    {
        $long = ip2long((string)($_SERVER['REMOTE_ADDR'] ?? ''));
        return $long === false ? 0 : (int)sprintf('%u', $long);
    }
}
