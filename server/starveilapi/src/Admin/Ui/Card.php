<?php
/**
 * 卡片容器：白底圆角框 + 标题，后台所有区块的外壳。
 *
 * 只负责套壳，标题与正文都按原样转义；正文由调用方拼好（可以是表单、表格、纯文本）。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Ui;

use Starveil\Api\Admin\View;

final class Card
{
    public static function render(string $title, string $body, string $desc = ''): string
    {
        return '<div class="sv-card"><div class="sv-section-title">' . View::e($title) . '</div>'
            . ($desc !== '' ? '<p class="sv-desc">' . View::e($desc) . '</p>' : '')
            . $body . '</div>';
    }
}
