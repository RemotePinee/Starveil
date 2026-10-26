<?php
/**
 * 相邻交换排序：列表页的「上移」「下移」都走这里。
 *
 * 先按 `sort,主键` 读出顺序，交换相邻两项后把 0..n-1 整段写回。老数据的
 * sort 可能全是 0，只对调两行的 sort 值等于什么都没做，整段重排才看得出变化。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

use think\Db;

final class Reorder
{
    /**
     * @param array<string,int> $scope 额外条件（如 topic_id）
     */
    public static function move(string $table, string $key, int $id, string $dir, array $scope = []): void
    {
        if ($id <= 0 || ($dir !== 'up' && $dir !== 'down')) {
            return;
        }
        $query = Db::table($table);
        if ($scope !== []) {
            $query = $query->where($scope);
        }
        $ids = array_map('intval', $query->order('sort asc,' . $key . ' asc')->column($key));
        $index = array_search($id, $ids, true);
        if ($index === false) {
            return;
        }
        $target = $dir === 'up' ? $index - 1 : $index + 1;
        if ($target < 0 || $target >= count($ids)) {
            return;
        }
        [$ids[$index], $ids[$target]] = [$ids[$target], $ids[$index]];
        foreach ($ids as $position => $rowId) {
            $row = Db::table($table);
            if ($scope !== []) {
                $row = $row->where($scope);
            }
            $row->where($key, $rowId)->update(['sort' => $position]);
        }
    }
}
