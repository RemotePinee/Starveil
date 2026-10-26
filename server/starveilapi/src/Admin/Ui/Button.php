<?php
/**
 * 按钮：主按钮、次级（ghost）、危险（danger）与一个隐藏 op 的小表单。
 *
 * 行内的小操作（删除 / 上移 / 下移 / 作废）都是 POST + CSRF，
 * 统一从这里出，调用方只管给 op、文案和要带上的隐藏字段。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Ui;

use Starveil\Api\Admin\View;

final class Button
{
    /**
     * @param array<string,int|string> $fields
     */
    public static function post(
        string $op,
        string $label,
        string $style,
        array $fields,
        string $csrf = '',
        bool $isCli = false
    ): string {
        $hidden = '<input type="hidden" name="op" value="' . View::e($op) . '">';
        foreach ($fields as $name => $value) {
            $hidden .= '<input type="hidden" name="' . View::e((string)$name)
                . '" value="' . View::e((string)$value) . '">';
        }
        return '<form method="post" class="sv-inline">'
            . Field::csrf($csrf, $isCli) . $hidden
            . '<button class="sv-btn ' . View::e($style) . ' sm" type="submit">' . View::e($label) . '</button></form>';
    }
}
