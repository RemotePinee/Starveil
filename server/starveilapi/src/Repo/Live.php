<?php
/**
 * 直播列表。
 *
 * 表是插件自己的 `cuican_zhibo`（字段：
 * `id`/`name`/`img`/`url`）。macCMS 核心的 `mac_live` 是另一种采集结构，
 * 不混用，避免升级核心时字段漂移。
 *
 * v2.5.5 起带分组：M3U 导入时把 `group-title` 存进 `gname`，
 * 几百个台可以按「央视 / 卫视 / 港澳台」这类原始分类浏览。
 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

use Starveil\Api\Installer;
use think\Db;

final class Live
{
    /** 未分组的归到这个名字下，客户端不需要自己拼文案。 */
    public const UNGROUPED = '未分组';

    /**
     * @param string $group 空串 = 全部；「未分组」= 库里 gname 为空的那批；其余按 gname 精确匹配
     * @return array{total:int,page:int,limit:int,groups:array,list:array}
     */
    public function list(int $page, int $limit, string $group = ''): array
    {
        $table = Installer::prefix() . 'cuican_zhibo';
        $group = trim($group);
        $isAll = $group === '';
        // 「未分组」是发给用户看的名字，库里存的是空串。
        // 不翻译回去的话，点这个分类会按 gname='未分组' 查，永远查不到任何频道。
        $dbGroup = $group === self::UNGROUPED ? '' : $group;

        // count 与 select 各建一个 Query：同一个 Query 先 count 再取列表会污染 bind
        $countQuery = Db::table($table)->where('status', 1);
        if (!$isAll) {
            $countQuery = $countQuery->where('gname', $dbGroup);
        }
        $total = (int)$countQuery->count();

        $query = Db::table($table)->where('status', 1);
        if (!$isAll) {
            $query = $query->where('gname', $dbGroup);
        }
        $rows = $query->order('sort asc,zhibo_id asc')->page($page, $limit)->select();

        $list = [];
        foreach ($rows as $row) {
            $list[] = [
                // id 按 long 用，这里保持数值而不是字符串
                'id' => (int)$row['zhibo_id'],
                'name' => (string)$row['name'],
                'img' => (string)$row['img'],
                'url' => (string)$row['url'],
                'group' => $this->groupName((string)($row['gname'] ?? '')),
            ];
        }
        return [
            'total' => $total,
            'page' => $page,
            'limit' => $limit,
            'groups' => $this->groups(),
            'list' => $list,
        ];
    }

    /**
     * 分组 + 每组频道数，给客户端的分类栏。
     *
     * 分组的**名单**来自 `cuican_zhibo_group`（运营可以先建好空分组再往里导），
     * 数量从频道表按 `gname` 现算。两边不一致时以频道数据为准：
     * 导入 M3U 会直接写 group-title，那些分组还没来得及登记进表里，
     * 所以最后再补一轮"频道有、表里没有"的分组，避免频道显示不出来。
     *
     * 总数放第一项（`group` 为空串），客户端不用单独发一次"全部"请求。
     *
     * @return array<int,array{group:string,count:int}>
     */
    public function groups(): array
    {
        $counts = $this->countsByGroup();
        $total = array_sum($counts);

        $groups = [['group' => '', 'count' => $total]];
        $seen = ['' => true];
        foreach (Db::table(Installer::prefix() . 'cuican_zhibo_group')
            ->order('sort asc,group_id asc')
            ->select() as $row) {
            $name = trim((string)($row['name'] ?? ''));
            if ($name === '' || isset($seen[$name])) {
                continue;
            }
            $seen[$name] = true;
            $groups[] = ['group' => $name, 'count' => $counts[$name] ?? 0];
        }
        // 频道里有、分组表里还没登记的（典型情况：刚导完 M3U），按数量从多到少补在后面
        $rest = [];
        foreach ($counts as $name => $count) {
            if ($name !== '' && !isset($seen[$name])) {
                $rest[$name] = $count;
            }
        }
        arsort($rest);
        foreach ($rest as $name => $count) {
            $groups[] = ['group' => (string)$name, 'count' => (int)$count];
        }
        return $groups;
    }

    /**
     * 每个分组下的启用频道数。键是分组名，空串是未分组。
     *
     * @return array<string,int>
     */
    private function countsByGroup(): array
    {
        $rows = Db::table(Installer::prefix() . 'cuican_zhibo')
            ->where('status', 1)
            ->field('gname, COUNT(*) AS cnt')
            ->group('gname')
            ->select();
        $counts = [];
        foreach ($rows as $row) {
            $name = $this->groupName((string)($row['gname'] ?? ''));
            $counts[$name] = ($counts[$name] ?? 0) + (int)($row['cnt'] ?? 0);
        }
        return $counts;
    }

    /** 空分组统一叫「未分组」，客户端与后台都不用各自特判。 */
    private function groupName(string $raw): string
    {
        $name = trim($raw);
        return $name === '' ? self::UNGROUPED : $name;
    }
}
