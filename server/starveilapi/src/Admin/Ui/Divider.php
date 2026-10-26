<?php
/**
 * 页内小标题：不分卡片，用来把一页切成两段。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Ui;

use Starveil\Api\Admin\View;

final class Divider
{
    public static function render(string $title): string
    {
        return '<div class="sv-divider">' . View::e($title) . '</div>';
    }
}
