<?php
/**
 * 消息中心：公告与推送。
 *
 * 表还是插件早就在用的 `cuican_message`（`user_id = 0` 即全站广播），
 * v2.6.5 起补了模板/跳转/强制/置顶这些列，并单开 `cuican_message_read` 存已读。
 *
 * template 只是客户端的排布开关：text 纯文字、notice / update 换顶部插画、
 * card 是整卡大图（cover 铺底、badge 挂左上角、cta 是主按钮文案）。
 *
 * 为什么已读要单开一张表：`cuican_message.status` 是全表共享的一列，
 * 谁先打开就等于替全站标记了已读；只有按 (msg_id, user_id) 存一行，
 * 每个用户才有自己的未读数。所以 `status` 保留但**不再参与未读判定**。
 *
 * 列名以 install/schema/v2_0_0.sql + v2_6_5.sql 为准（msg_id / user_id / title /
 * content / status / created_at，以及 v2.6.5 新增的 kind…pinned）。
 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

use Starveil\Api\Installer;
use think\Db;

final class Push
{
    /** user_id=0 即全站广播，与列表接口的取数条件一致。 */
    public const BROADCAST_USER_ID = 0;

    /** kind 列：1=推送（运营活动），2=公告（系统通知）。接口层映射成字符串下发。 */
    public const KIND_PUSH = 1;
    public const KIND_ANNOUNCE = 2;

    /**
     * 枚举白名单。
     *
     * 后台下拉可以被改包绕过，写库前统一按这几组收敛：非法值一律退回默认，
     * 免得库里出现 `template=xxx` 这种客户端认不出、又不至于报错的半坏数据。
     */
    private const KIND_NAMES = [self::KIND_PUSH => 'push', self::KIND_ANNOUNCE => 'announce'];
    private const TEMPLATES = ['text', 'notice', 'update', 'card'];
    private const ACTIONS = ['none', 'vod', 'type', 'url'];

    /** 一次最多标记这么多条：客户端翻页是 20 条一页，200 足够，也挡住一万个 id 的刷库请求。 */
    private const READ_BATCH = 200;

    /** @return array{list:array<int,array<string,mixed>>,total:int,unread:int} */
    public function list(int $userId, int $page, int $size): array
    {
        $table = $this->table();
        $total = (int)Db::table($table)
            ->where('user_id', self::BROADCAST_USER_ID)
            ->count();

        // 置顶排最前，其余按消息 id 倒序（= 发布时间倒序，id 就是自增时间序）
        $rows = Db::table($table)
            ->where('user_id', self::BROADCAST_USER_ID)
            ->order('pinned desc,msg_id desc')
            ->page($page, $size)
            ->select();

        $ids = array_map(static fn (array $row): int => (int)$row['msg_id'], $rows);
        $read = $this->readIds($userId, $ids);

        $list = [];
        foreach ($rows as $row) {
            $list[] = $this->item($row, isset($read[(int)$row['msg_id']]));
        }

        return ['list' => $list, 'total' => $total, 'unread' => $this->unreadCount($userId)];
    }

    /**
     * 未读条数。
     *
     * 未登录（user_id <= 0）没有阅读记录，整批广播都算未读——与列表里 `read` 恒为 false
     * 一致；这不代表"有人没看"，只是客户端没有身份可以记。
     */
    public function unreadCount(int $userId): int
    {
        $total = (int)Db::table($this->table())
            ->where('user_id', self::BROADCAST_USER_ID)
            ->count();
        if ($userId <= 0) {
            return $total;
        }
        $read = (int)Db::table($this->readTable())->where('user_id', $userId)->count();
        // 广播被手工删掉后，read 行会变成孤儿；钳到 0，别让红点上出现负数
        return max(0, $total - $read);
    }

    /**
     * 删除一条广播。
     *
     * 已读记录一并清掉：留着的话，将来同 msg_id 的记录复用时会被当成"用户已读"，
     * 表现为发出去却没人收到弹窗，排查起来非常难。
     */
    public function remove(int $msgId): bool
    {
        if ($msgId <= 0) {
            return false;
        }
        Db::table($this->readTable())->where('msg_id', $msgId)->delete();
        Db::table($this->table())
            ->where('msg_id', $msgId)
            ->where('user_id', self::BROADCAST_USER_ID)
            ->delete();
        return true;
    }

    /**
     * 批量标记已读。
     *
     * 只认库里真实存在的广播：客户端传上来的 id 不可信，不能让它往已读表里写任意 msg_id。
     * 先查后插 + 逐条捕获 1062：同一用户并发点两次时第二次会撞主键，
     * 忽略即可（结果一样是"已读"），不必为这种请求回一个错误。
     *
     * @param array<int,int|string> $ids
     */
    public function markRead(int $userId, array $ids): void
    {
        if ($userId <= 0) {
            return;
        }
        $ids = $this->cleanIds($ids);
        if ($ids === []) {
            return;
        }

        $known = array_map('intval', Db::table($this->table())
            ->where('user_id', self::BROADCAST_USER_ID)
            ->whereIn('msg_id', $ids)
            ->column('msg_id'));
        $known = array_values(array_intersect($ids, $known));
        if ($known === []) {
            return;
        }

        $done = array_map('intval', Db::table($this->readTable())
            ->where('user_id', $userId)
            ->whereIn('msg_id', $known)
            ->column('msg_id'));
        $pending = array_values(array_diff($known, $done));

        $table = $this->readTable();
        foreach ($pending as $msgId) {
            try {
                Db::table($table)->insert([
                    'msg_id' => $msgId,
                    'user_id' => $userId,
                    'read_at' => time(),
                ]);
            } catch (\Throwable $e) {
                // 1062 唯一键冲突：并发请求已经写过，等价于成功；其它错误继续抛给 Kernel 兜底
                if (!str_contains($e->getMessage(), '1062')) {
                    throw $e;
                }
            }
        }
    }

    /** 今天（服务器本地时区）发出的广播条数，只给后台核对用。 */
    public function todayCount(): int
    {
        return (int)Db::table($this->table())
            ->where('user_id', self::BROADCAST_USER_ID)
            ->where('created_at', '>=', strtotime('today'))
            ->count();
    }

    /**
     * 写一条全站广播。
     *
     * 参数收成一个数组：后台表单有十来个字段，逐个位置参数既容易错位，
     * 调用方还得记住第 7 个是跳转类型还是跳转参数。非法值在这里统一收敛。
     *
     * @param array<string,mixed> $fields
     */
    public function publish(array $fields): void
    {
        $kind = (int)($fields['kind'] ?? self::KIND_PUSH);
        $template = (string)($fields['template'] ?? 'text');
        $action = (string)($fields['action_type'] ?? 'none');

        Db::table($this->table())->insert([
            'user_id' => self::BROADCAST_USER_ID,
            'title' => mb_substr(trim((string)($fields['title'] ?? '')), 0, 128),
            'content' => (string)($fields['content'] ?? ''),
            'kind' => isset(self::KIND_NAMES[$kind]) ? $kind : self::KIND_PUSH,
            'template' => in_array($template, self::TEMPLATES, true) ? $template : 'text',
            'cover' => mb_substr(trim((string)($fields['cover'] ?? '')), 0, 255),
            'icon' => mb_substr(trim((string)($fields['icon'] ?? '')), 0, 32),
            'badge' => mb_substr(trim((string)($fields['badge'] ?? '')), 0, 16),
            'cta' => mb_substr(trim((string)($fields['cta'] ?? '')), 0, 32),
            'action_type' => in_array($action, self::ACTIONS, true) ? $action : 'none',
            'action_value' => mb_substr(trim((string)($fields['action_value'] ?? '')), 0, 255),
            'forced' => (int)!empty($fields['forced']),
            'pinned' => (int)!empty($fields['pinned']),
            // 老列，保留写 0：回滚到旧版本插件时仍按"未读"显示
            'status' => 0,
            'created_at' => time(),
        ]);
    }

    /**
     * 当前用户读过的消息 id。
     *
     * 先取当页 id 再查已读表，而不是 LEFT JOIN：join 之后的列别名在
     * `Db::table()` 的字符串表名上不好写，还得处理 `read_at` 与前缀重名；
     * 消息表单表几十行，多一条按主键的查询更直观。
     *
     * @param array<int,int> $ids
     * @return array<int,bool> 已读的 msg_id => true
     */
    private function readIds(int $userId, array $ids): array
    {
        if ($userId <= 0 || $ids === []) {
            return [];
        }
        $rows = Db::table($this->readTable())
            ->where('user_id', $userId)
            ->whereIn('msg_id', $ids)
            ->column('msg_id');
        $out = [];
        foreach ($rows as $id) {
            $out[(int)$id] = true;
        }
        return $out;
    }

    /** @param array<string,mixed> $row */
    private function item(array $row, bool $read): array
    {
        $kind = (int)($row['kind'] ?? self::KIND_PUSH);

        return [
            'id' => (int)$row['msg_id'],
            // 库里是 tinyint，客户端认的是字符串（announce/push），转换只在这一处
            'kind' => self::KIND_NAMES[$kind] ?? 'push',
            'template' => (string)($row['template'] ?? 'text'),
            'title' => (string)($row['title'] ?? ''),
            'content' => (string)($row['content'] ?? ''),
            'cover' => (string)($row['cover'] ?? ''),
            'icon' => (string)($row['icon'] ?? ''),
            'badge' => (string)($row['badge'] ?? ''),
            'cta' => (string)($row['cta'] ?? ''),
            'action_type' => (string)($row['action_type'] ?? 'none'),
            'action_value' => (string)($row['action_value'] ?? ''),
            'created_at' => (int)($row['created_at'] ?? 0),
            'read' => $read,
            // 强制弹出：客户端凭它禁掉"不再提示"和点外部关闭
            'force' => !empty($row['forced']),
            // 置顶：列表排序已经在 SQL 里做过，这里一并回传供客户端排序兜底
            'pinned' => !empty($row['pinned']),
        ];
    }

    /**
     * 去重、只留正整数、截到 READ_BATCH。
     *
     * @param array<int,int|string> $ids
     * @return array<int,int>
     */
    private function cleanIds(array $ids): array
    {
        $clean = [];
        foreach ($ids as $id) {
            $id = (int)$id;
            if ($id > 0) {
                $clean[$id] = $id;
            }
        }
        return array_slice(array_values($clean), 0, self::READ_BATCH);
    }

    private function table(): string
    {
        return Installer::prefix() . 'cuican_message';
    }

    private function readTable(): string
    {
        return Installer::prefix() . 'cuican_message_read';
    }
}
