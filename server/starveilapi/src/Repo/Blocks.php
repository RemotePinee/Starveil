<?php
/**
 * 首页专区（一组建档的影片卡片）。
 *
 * 对齐老接口 `v1.main/category?need_vod=1`：读插件表 `mac_category`，
 * 用 `void_id` 逗号串存影片；这里改成 block + block_vod 两张表，排序可控。
 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

use Starveil\Api\Installer;
use think\Db;

final class Blocks
{
    public function __construct(private readonly Vods $vods = new Vods())
    {
    }

    /**
     * @return array<int,array{id:int,title:string,subtitle:string,layout:string,vods:array}>
     */
    public function list(string $position = 'home', bool $withVods = true, int $typeId = 0): array
    {
        $query = Db::table(Installer::prefix() . 'cuican_block')
            ->where('position', $position)
            ->where('status', 1);
        // 没跑 v2_5_2 时列还不存在：忽略分类过滤，别让首页专区整块 500 掉
        if (self::hasTypeColumn()) {
            $query->where('type_id', $typeId);
        }
        $rows = $query
            ->order('sort asc,id asc')
            ->limit(20)
            ->select();

        $out = [];
        foreach ($rows as $row) {
            $out[] = [
                'id' => (int)$row['id'],
                'title' => (string)$row['title'],
                'subtitle' => (string)$row['subtitle'],
                'layout' => (string)$row['layout'],
                'vods' => $withVods ? $this->vodsOf((int)$row['id']) : [],
            ];
        }
        return $out;
    }

    /** `cuican_block.type_id` 是否存在（v2_5_2 迁移）。静态缓存，一次请求只查一次。 */
    private static function hasTypeColumn(): bool
    {
        static $has = null;
        if ($has !== null) {
            return $has;
        }
        try {
            $rows = Db::query(
                'SHOW COLUMNS FROM ' . Installer::prefix() . 'cuican_block LIKE ' . Db::quote('type_id')
            );
            $has = !empty($rows);
        } catch (\Throwable $e) {
            $has = false;
        }
        return $has;
    }
    /** @return array<int,array> 影片按专区内的 sort 排序，已下架的自动跳过。 */
    private function vodsOf(int $blockId): array
    {
        $ids = Db::table(Installer::prefix() . 'cuican_block_vod')
            ->where('block_id', $blockId)
            ->order('sort asc,id asc')
            ->column('vod_id');
        if ($ids === []) {
            return [];
        }
        $rows = Db::name('vod')
            ->whereIn('vod_id', array_map('intval', $ids))
            ->where('vod_status', 1)
            ->select();

        $byId = [];
        foreach ($rows as $row) {
            $byId[(int)$row['vod_id']] = $this->vods->toDto($row);
        }
        $ordered = [];
        foreach ($ids as $id) {
            $id = (int)$id;
            if (isset($byId[$id])) {
                $ordered[] = $byId[$id];
            }
        }
        return $ordered;
    }
}
