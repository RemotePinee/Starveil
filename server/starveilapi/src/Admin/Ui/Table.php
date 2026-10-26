<?php
/**
 * 表格容器：卡片 + 表头 + 行，空态统一渲染成一格 `sv-empty`。
 *
 * 行内容（`$body`）由调用方拼，这里只管表头与空态，避免每页各写一遍 colspan。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Ui;

use Starveil\Api\Admin\View;

final class Table
{
    /**
     * @param array<int,string> $headers
     */
    public static function render(
        array $headers,
        string $body,
        int $colspan,
        string $empty = '还没有数据',
        string $title = ''
    ): string {
        $head = '';
        foreach ($headers as $header) {
            $head .= '<th>' . View::e($header) . '</th>';
        }
        $rows = $body !== ''
            ? $body
            : '<tr><td colspan="' . $colspan . '" class="sv-empty">' . View::e($empty) . '</td></tr>';
        return '<div class="sv-card">'
            . ($title !== '' ? '<div class="sv-section-title">' . View::e($title) . '</div>' : '')
            . '<table class="sv-table"><tr>' . $head . '</tr>' . $rows . '</table></div>';
    }
}
