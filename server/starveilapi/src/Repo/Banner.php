<?php
/**
 * 首页轮播。
 *
 * 不读 macCMS 的 `level=9` 推荐值（那样换轮播要改影片推荐等级）；
 * 改成插件自己的表，运营可以选片、外链、排序、启停，不再依赖 macCMS 字段。
 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

use Starveil\Api\Installer;
use Starveil\Api\Support\Setting;
use think\Db;

final class Banner
{
    /**
     * @return array<int,array{id:int,title:string,subtitle:string,pic:string,vod_id:int,url:string,style:string}>
     */
    public function list(): array
    {
        $limit = min(max(Setting::int('banner_limit', 6), 1), 10);
        $rows = Db::table(Installer::prefix() . 'cuican_banner')
            ->where('status', 1)
            ->order('sort asc,id asc')
            ->limit($limit)
            ->select();

        $vodIds = array_values(array_filter(array_map(
            static fn (array $row): int => (int)$row['vod_id'],
            $rows,
        ), static fn (int $id): bool => $id > 0));
        // 只填了影片 ID 的轮播：拿影片的图兜底，否则客户端只能显示"暂无推荐内容"。
        // macCMS 的 vod_pic 是竖版海报，塞进 16:9 的轮播位会被左右裁掉一大截；
        // 缩略图/海报图才是横的，所以按 thumb -> slide -> pic 的顺序挑第一张能用的。
        $vods = [];
        if ($vodIds !== []) {
            $rowsVod = Db::table(Installer::prefix() . 'vod')
                ->whereIn('vod_id', $vodIds)
                ->field('vod_id,vod_name,vod_pic,vod_pic_thumb,vod_pic_slide')
                ->select();
            foreach ($rowsVod as $vod) {
                $vods[(int)$vod['vod_id']] = $vod;
            }
        }

        return array_map(static function (array $row) use ($vods): array {
            $vodId = (int)$row['vod_id'];
            $pic = trim((string)$row['pic']);
            $title = trim((string)$row['title']);
            if ($vodId > 0) {
                $vod = $vods[$vodId] ?? [];
                // 运营没填图片才回落到影片图（填了就是明确指定，不覆盖）。
                // 判空必须连 "0" 一起算：后台表单空值会存成字符串 "0"，
                // 只判空串的话会把它当图片地址下发，客户端加载失败就显示空状态。
                if ($pic === '' || $pic === '0') {
                    $pic = self::widePic($vod);
                }
                if ($title === '' || $title === '0') {
                    $title = (string)($vod['vod_name'] ?? '');
                }
            }
            return [
                'id' => (int)$row['id'],
                'title' => $title,
                'subtitle' => (string)$row['subtitle'],
                'pic' => $pic,
                'vod_id' => $vodId,
                'url' => (string)$row['url'],
                'style' => (string)$row['style'],
            ];
        }, $rows);
    }

    /**
     * 取影片最"横"的一张图：缩略图优先，其次海报图，最后才退回竖版 vod_pic。
     *
     * 后台这三个字段都可能是空串或字符串 "0"，所以逐个判到能用的为止。
     *
     * @param array<string,mixed> $vod
     */
    private static function widePic(array $vod): string
    {
        foreach (['vod_pic_thumb', 'vod_pic_slide', 'vod_pic'] as $field) {
            $value = trim((string)($vod[$field] ?? ''));
            if ($value !== '' && $value !== '0') {
                return $value;
            }
        }

        return '';
    }
}
