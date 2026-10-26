<?php
/**
 * 分页条：上一页 / 第 N / M 页 / 下一页，靠 GET 的 `page` 参数翻页。
 *
 * 只做展示，页码越界由调用方先夹好（删完一整页时页码会超出去）。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Ui;

final class Pager
{
    /**
     * @param string $key     页面键（`?a=` 的取值）
     * @param int    $current 当前页，从 1 开始
     * @param int    $pages   总页数，至少 1
     * @param int    $total   总条数，只用于文案
     */
    public static function render(string $key, int $current, int $pages, int $total): string
    {
        return '<div class="sv-actions sv-actions-end">'
            . ($current > 1
                ? '<a class="sv-btn ghost sm" href="?a=' . $key . '&amp;page=' . ($current - 1) . '">上一页</a>'
                : '')
            . '<span class="sv-desc">第 ' . $current . ' / ' . $pages . ' 页，共 ' . $total . ' 条</span>'
            . ($current < $pages
                ? '<a class="sv-btn ghost sm" href="?a=' . $key . '&amp;page=' . ($current + 1) . '">下一页</a>'
                : '')
            . '</div>';
    }
}
