<?php
/** 首页快捷入口（搜索栏下方那排胶囊）。 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

use Starveil\Api\Installer;
use Starveil\Api\Support\Media;
use think\Db;

/**
 * 首页快捷入口。
 *
 * 卡片是「背景图 + 标题 + 副标题」，不再下发内置图标；`bg` 在这里转成绝对地址，
 * 因为 App 的图片加载没有 baseUrl，相对路径会直接失败。
 */
final class Links
{
    /** @return array<int,array{title:string,subtitle:string,bg:string,target_type:string,target_id:int,url:string}> */
    public function list(): array
    {
        $rows = Db::table(Installer::prefix() . 'cuican_link')
            ->where('status', 1)
            ->order('sort asc,id asc')
            ->limit(12)
            ->select();

        $covers = $this->coverFallback($rows);

        return array_map(static function (array $row) use ($covers): array {
            $targetId = (int)$row['target_id'];
            $bg = trim((string)($row['bg'] ?? ''));
            if ($bg === '') {
                // 没传图：影片用海报、专题用封面、直播用截图，运营只填 id 即可
                $bg = $covers[$row['target_type'] . ':' . $targetId] ?? '';
            }
            return [
                'title' => (string)$row['title'],
                'subtitle' => (string)($row['subtitle'] ?? ''),
                'bg' => Media::url($bg),
                'target_type' => (string)$row['target_type'],
                'target_id' => $targetId,
                'url' => (string)$row['url'],
            ];
        }, $rows);
    }

    /**
     * 只配了跳转对象、没配背景图的入口：按类型批量取封面。
     *
     * 影片 → mac_vod.vod_pic；专题 → cuican_topic.pic；直播 → cuican_zhibo.img。
     * 已下架/停用的对象不取，拿不到就保持空串，客户端显示纯色底。
     * 与轮播的兜底同一口径（`Repo\Banner`）：运营只想"点这个片子/专题/直播"时，
     * 不该逼他再传一张图。
     *
     * @param array<int,array> $rows
     * @return array<string,string> 键为 "type:id"
     */
    private function coverFallback(array $rows): array
    {
        $ids = ['vod' => [], 'topic' => [], 'live' => []];
        foreach ($rows as $row) {
            $type = (string)$row['target_type'];
            $id = (int)$row['target_id'];
            if ($id > 0 && trim((string)($row['bg'] ?? '')) === '' && isset($ids[$type])) {
                $ids[$type][$id] = true;
            }
        }

        $out = [];
        if ($ids['vod'] !== []) {
            $vods = Db::name('vod')
                ->whereIn('vod_id', array_keys($ids['vod']))
                ->where('vod_status', 1)
                ->field('vod_id,vod_pic')
                ->select();
            foreach ($vods as $vod) {
                $pic = trim((string)$vod['vod_pic']);
                if ($pic !== '') {
                    $out['vod:' . (int)$vod['vod_id']] = $pic;
                }
            }
        }
        if ($ids['topic'] !== []) {
            $topics = Db::table(Installer::prefix() . 'cuican_topic')
                ->whereIn('topic_id', array_keys($ids['topic']))
                ->where('status', 1)
                ->field('topic_id,pic')
                ->select();
            foreach ($topics as $topic) {
                $pic = trim((string)$topic['pic']);
                if ($pic !== '') {
                    $out['topic:' . (int)$topic['topic_id']] = $pic;
                }
            }
        }
        if ($ids['live'] !== []) {
            $lives = Db::table(Installer::prefix() . 'cuican_zhibo')
                ->whereIn('zhibo_id', array_keys($ids['live']))
                ->where('status', 1)
                ->field('zhibo_id,img')
                ->select();
            foreach ($lives as $live) {
                $pic = trim((string)$live['img']);
                if ($pic !== '') {
                    $out['live:' . (int)$live['zhibo_id']] = $pic;
                }
            }
        }
        return $out;
    }
}
