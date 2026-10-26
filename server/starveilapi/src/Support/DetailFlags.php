<?php
/**
 * 详情页区块开关。
 *
 * 详情页的"相关推荐 / 评分 / 评论"三块，运营希望能各自关掉（例如纯短剧站不需要评分）。
 * 走 cuican_setting 的普通设置项，不建表：键名与默认值集中在这里，
 * 后台设置页和 `v1.vod/detail` 都从这里取，避免两边各写一份字符串。
 *
 * 值只有 1（显示）与 0（隐藏）。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

final class DetailFlags
{
    public const RECOMMEND = 'detail_recommend';
    public const SCORE = 'detail_score';
    public const COMMENT = 'detail_comment';

    /** 设置项键名 → 默认值；后台渲染开关时也用它列项。 */
    public const DEFAULTS = [
        self::RECOMMEND => 1,
        self::SCORE => 1,
        self::COMMENT => 1,
    ];

    /** @return array{recommend:int,score:int,comment:int} */
    public static function all(): array
    {
        return [
            'recommend' => self::flag(self::RECOMMEND),
            'score' => self::flag(self::SCORE),
            'comment' => self::flag(self::COMMENT),
        ];
    }

    private static function flag(string $key): int
    {
        return Setting::int($key, self::DEFAULTS[$key] ?? 0) === 1 ? 1 : 0;
    }
}
