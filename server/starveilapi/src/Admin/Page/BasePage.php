<?php
/**
 * 页面类基类：只放「每个后台页都要用一次」的东西——CSRF、cli 标记、几个控件快捷方法。
 *
 * 页面类统一契约（HomePage / FeaturedPage / PushPage 早就是这个形状）：
 *   render(): array{title:string,html:string}
 *   mutate(array $post): void
 * 写操作的校验失败统一走 [Guard]，这里不提供 fail()：每页回哪个页面是页面自己的事。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Admin\Ui\Button;
use Starveil\Api\Admin\Ui\Card;
use Starveil\Api\Admin\Ui\Field;
use Starveil\Api\Admin\Ui\Table;
use Starveil\Api\Admin\View;

abstract class BasePage
{
    public function __construct(
        protected readonly string $csrf = '',
        protected readonly bool $isCli = false,
    ) {
    }

    /** 输出转义：页面里所有动态内容都从这里过。 */
    protected static function e(mixed $value): string
    {
        return View::e($value);
    }

    protected function csrfField(): string
    {
        return Field::csrf($this->csrf, $this->isCli);
    }

    protected function card(string $title, string $body, string $desc = ''): string
    {
        return Card::render($title, $body, $desc);
    }

    /** @param array<int,string> $headers */
    protected function table(array $headers, string $body, int $colspan): string
    {
        return Table::render($headers, $body, $colspan);
    }

    protected function field(string $label, string $name, string $value = '', string $hint = ''): string
    {
        return Field::input($label, $name, $value, $hint);
    }

    protected function textarea(string $label, string $name, string $value, string $hint = ''): string
    {
        return Field::textarea($label, $name, $value, $hint);
    }

    /** @param array<string,string> $options */
    protected function selectField(
        string $label,
        string $name,
        array $options,
        string $selected = '',
        string $hint = ''
    ): string {
        return Field::select($label, $name, $options, $selected, $hint);
    }

    /** @param array<string,int|string> $fields */
    protected function postButton(string $op, string $label, string $style, array $fields): string
    {
        return Button::post($op, $label, $style, $fields, $this->csrf, $this->isCli);
    }
}
