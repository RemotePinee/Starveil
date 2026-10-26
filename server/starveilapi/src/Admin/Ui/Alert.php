<?php
/**
 * 提示条：校验失败的红色行内提示、操作成功的绿色行内提示。
 *
 * 与 toast 的区别：那个是前端弹一下就没，这里是留在页面上、刷新后仍在的一条。
 * 颜色写在这里是因为它是组件的一部分，不属于业务页面。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Ui;

use Starveil\Api\Admin\View;

final class Alert
{
    public static function error(string $message): string
    {
        return '<p class="sv-hint sv-flash-err">' . View::e($message) . '</p>';
    }

    public static function ok(string $message): string
    {
        return '<p class="sv-hint sv-flash-ok">' . View::e($message) . '</p>';
    }
}
