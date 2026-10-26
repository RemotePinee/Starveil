<?php
/**
 * 首页卡片位：编辑精选 / 热度榜 / 猜你喜欢。
 *
 * 不读云端 `v1.card` / `level` 推荐值：那样换片要改 macCMS 的推荐等级，
 * 既和轮播抢同一个字段，也没法排序。这里复用「首页专区」那两张表，
 * 用 position 区分三组固定区块，运营可以分别指定影片和顺序：
 * - featured：首页第一张卡片位（`v1.main/featured`）
 * - hot：热度榜
 * - guess：猜你喜欢
 *
 * 每个 position 只取 sort 最小的启用组；组不存在、组停用、组里没有已上架影片时返回空列表，
 * 客户端按"没有这个卡片位"降级，不报错。
 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

final class Featured
{
    public const DEFAULT_TITLE = '编辑精选';

    /** cuican_block.position 上的约定值，后台固定只维护这一组。 */
    public const POSITION = 'featured';

    /** 首页热度榜。 */
    public const POSITION_HOT = 'hot';

    /** 首页猜你喜欢。 */
    public const POSITION_GUESS = 'guess';

    /** position → 默认标题：运营没填标题时客户端显示它。 */
    private const TITLES = [
        self::POSITION => self::DEFAULT_TITLE,
        self::POSITION_HOT => '热度榜',
        self::POSITION_GUESS => '猜你喜欢',
    ];

    public function __construct(private readonly Vods $vods = new Vods())
    {
    }

    /**
     * @return array{title:string,subtitle:string,vods:array}
     */
    public function payload(string $position = self::POSITION, int $typeId = 0): array
    {
        $block = $this->findBlock($position, $typeId);
        $default = self::titleOf($position);

        if ($block === null) {
            return ['title' => $default, 'subtitle' => '', 'vods' => []];
        }

        $title = trim((string)$block['title']);

        return [
            'title' => $title !== '' ? $title : $default,
            'subtitle' => trim((string)$block['subtitle']),
            'vods' => $block['vods'],
        ];
    }

    /**
     * 取该分类的组；没配或组里没有已上架影片就返回 null。
     *
     * 不做"回退全局"：`type_id=0` 只属于首页，分类页没配时客户端用该分类
     * 自己的列表切片，避免所有分类又显示同一批片。
     *
     * @return array{id:int,title:string,subtitle:string,layout:string,vods:array}|null
     */
    private function findBlock(string $position, int $typeId): ?array
    {
        foreach ((new Blocks($this->vods))->list($position, true, $typeId) as $block) {
            if ($block['vods'] !== []) {
                return $block;
            }
        }
        return null;
    }

    /** position 的默认名；未知 position 回落到编辑精选，避免客户端出现空标题。 */
    public static function titleOf(string $position): string
    {
        return self::TITLES[$position] ?? self::DEFAULT_TITLE;
    }
}
