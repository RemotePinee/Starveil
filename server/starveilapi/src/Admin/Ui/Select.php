<?php
/**
 * 自定义下拉：原生 `select` 留作数据源（表单提交、JS 取值都照旧），
 * 看得见的部分是按钮 + 列表，避免浏览器原生弹层的方角和系统蓝高亮。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Ui;

use Starveil\Api\Admin\View;

final class Select
{
    /**
     * @param array<string,string> $options
     */
    public static function render(
        string $name,
        array $options,
        string $selected = '',
        string $class = '',
        bool $disabled = false
    ): string {
        $selected = (string)$selected;
        $label = $options[$selected] ?? (string)reset($options);
        $items = '';
        $native = '';
        foreach ($options as $value => $text) {
            $value = (string)$value;
            $on = $value === $selected;
            $items .= '<button type="button" class="sv-select-opt' . ($on ? ' is-selected' : '') . '"'
                . ' role="option" aria-selected="' . ($on ? 'true' : 'false') . '"'
                . ' data-value="' . View::e($value) . '">' . View::e($text) . '</button>';
            $native .= '<option value="' . View::e($value) . '"' . ($on ? ' selected' : '') . '>'
                . View::e($text) . '</option>';
        }
        $lock = $disabled ? ' disabled title="此项固定，不可修改"' : '';
        // 没有 name 时不出 name 属性：行编辑器里的下拉只是 JS 数据源，不该混进表单提交
        $nameAttr = $name === '' ? '' : ' name="' . View::e($name) . '"';
        return '<div class="sv-select" data-sv-select>'
            . '<button type="button" class="sv-select-btn" aria-haspopup="listbox" aria-expanded="false"'
            . $lock . '><span class="sv-select-label">' . View::e((string)$label) . '</span>'
            . '<i class="sv-select-caret"></i></button>'
            . '<div class="sv-select-menu" role="listbox" hidden>' . $items . '</div>'
            . '<select class="sv-select-native' . ($class !== '' ? ' ' . View::e($class) : '') . '"'
            . $nameAttr . $lock . ' hidden>' . $native . '</select></div>';
    }
}
