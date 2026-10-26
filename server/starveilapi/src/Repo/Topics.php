<?php
/**
 * 专题数据。
 *
 * 表是插件自己的 `cuican_topic` / `cuican_topic_vod`，不碰 macCMS 的 `mac_topic`
 * （核心那套是另一套字段与用途）。详情里的 `vod_list` 直接复用 Vods 的 DTO，
 * 客户端两个页面的影片卡片是同一个模型。
 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

use Starveil\Api\Installer;
use think\Db;

final class Topics
{
    /** @return array{total:int,page:int,limit:int,list:array} */
    public function list(int $page, int $limit): array
    {
        $table = Installer::prefix() . 'cuican_topic';
        $total = Db::table($table)->where('status', 1)->count();
        $rows = Db::table($table)
            ->where('status', 1)
            ->order('sort asc,topic_id desc')
            ->page($page, $limit)
            ->select();

        $list = [];
        foreach ($rows as $row) {
            $list[] = [
                'topic_id' => (string)$row['topic_id'],
                'topic_name' => (string)$row['name'],
                'topic_pic' => (string)$row['pic'],
                'topic_blurb' => (string)$row['blurb'],
                'topic_content' => (string)($row['content'] ?? ''),
            ];
        }
        return ['total' => (int)$total, 'page' => $page, 'limit' => $limit, 'list' => $list];
    }

    public function detail(string $topicId): ?array
    {
        $id = (int)$topicId;
        if ($id <= 0) {
            return null;
        }
        $table = Installer::prefix() . 'cuican_topic';
        $row = Db::table($table)->where('topic_id', $id)->where('status', 1)->find();
        if (!$row) {
            return null;
        }

        $vodIds = Db::table(Installer::prefix() . 'cuican_topic_vod')
            ->where('topic_id', $id)
            ->column('vod_id');
        $vods = (new Vods())->byIds(array_map('intval', $vodIds));

        return [
            'topic_name' => (string)$row['name'],
            'topic_pic' => (string)$row['pic'],
            'topic_blurb' => (string)$row['blurb'],
            'topic_content' => (string)($row['content'] ?? ''),
            'topic_tag' => (string)($row['blurb'] ?? ''),
            'vod_list' => $vods,
        ];
    }
}
