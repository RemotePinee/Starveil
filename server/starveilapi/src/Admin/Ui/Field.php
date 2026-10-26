<?php
/**
 * 表单控件：文本框 / 下拉（带标签）/ 多行文本 / CSRF 隐藏域。
 *
 * 下拉的可见部分交给 [Select]，这里只补标签与说明，保持 `.sv-field` 一层结构，
 * 后台 CSS 的 `.sv-field` 规则才对得上。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Ui;

use Starveil\Api\Admin\View;

final class Field
{
    public static function input(string $label, string $name, string $value = '', string $hint = ''): string
    {
        return '<label class="sv-field"><span>' . View::e($label) . '</span>'
            . '<input name="' . View::e($name) . '" value="' . View::e($value) . '">'
            . ($hint !== '' ? '<small>' . View::e($hint) . '</small>' : '') . '</label>';
    }

    /**
     * @param array<string,string> $options
     */
    public static function select(
        string $label,
        string $name,
        array $options,
        string $selected = '',
        string $hint = ''
    ): string {
        return '<div class="sv-field"><span>' . View::e($label) . '</span>'
            . Select::render($name, $options, $selected)
            . ($hint !== '' ? '<small>' . View::e($hint) . '</small>' : '') . '</div>';
    }

    public static function textarea(string $label, string $name, string $value, string $hint = ''): string
    {
        return '<label class="sv-field sv-full"><span>' . View::e($label) . '</span>'
            . '<textarea name="' . View::e($name) . '">' . View::e($value) . '</textarea>'
            . ($hint !== '' ? '<small>' . View::e($hint) . '</small>' : '') . '</label>';
    }

    /**
     * CSRF 隐藏域。CLI 自检没有会话，不出这个字段（也就不会被 CSRF 拦下）。
     */
    public static function csrf(string $csrf, bool $isCli): string
    {
        return $isCli ? '' : '<input type="hidden" name="_token" value="' . View::e($csrf) . '">';
    }
}
