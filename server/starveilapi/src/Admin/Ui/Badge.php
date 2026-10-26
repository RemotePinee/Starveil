<?php
/**
 * 状态徽标：`sv-badge` + 色调（ok / warn / off）。
 *
 * 启用停用是后台出现最多的一对，单独给个 onOff()，免得每页再写一遍三元。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Ui;

use Starveil\Api\Admin\View;

final class Badge
{
    public static function render(string $tone, string $label): string
    {
        return '<span class="sv-badge ' . View::e($tone) . '">' . View::e($label) . '</span>';
    }

    /** 启用（ok）/ 停用（off）。 */
    public static function onOff(bool $on): string
    {
        return $on ? self::render('ok', '启用') : self::render('off', '停用');
    }
}
