<?php
/**
 * 后台路由：`?a=` 交给哪个页面类，POST 交给它自己的写操作。
 *
 * 只剩分发。页面实现在 `Page/`，控件在 `Ui/`，样式与脚本在 `assets/`。
 * 首页装修（HomePage / FeaturedPage）与推送页（PushPage）形状早就是页面类的样子，
 * 直接按页面键转过去。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin;

use Starveil\Api\Admin\Page\BasePage;
use Starveil\Api\Admin\Page\CardsPage;
use Starveil\Api\Admin\Page\DashboardPage;
use Starveil\Api\Admin\Page\LivePage;
use Starveil\Api\Admin\Page\OrdersPage;
use Starveil\Api\Admin\Page\PayPage;
use Starveil\Api\Admin\Page\SettingsPage;
use Starveil\Api\Admin\Page\TopicsPage;
use Starveil\Api\Admin\Page\UsersPage;
use Starveil\Api\Admin\Page\WithdrawPage;

final class Actions
{
    public function __construct(private readonly bool $isCli = false, private readonly string $csrf = '')
    {
    }

    /** @return array{title:string,html:string} */
    public function handle(string $page, array $post): array
    {
        $isPost = $this->isCli ? ($post !== []) : (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST');
        if ($isPost) {
            $this->mutate($page, $post);
        }
        if (HomePage::supports($page)) {
            return (new HomePage($this->csrf, $this->isCli))->render($page);
        }
        return $this->page($page)->render();
    }

    private function mutate(string $page, array $post): void
    {
        if (HomePage::supports($page)) {
            (new HomePage($this->csrf, $this->isCli))->mutate($page, $post);
            return;
        }
        $this->page($page)->mutate($post);
    }

    /**
     * 卡密导出：GET 直出 CSV，不经过 layout（否则正文外会多包一层 HTML）。
     * admin.php 的 `?export=1` 与 CLI `cards export` 都调这里。
     */
    public function exportCards(): void
    {
        (new CardsPage($this->csrf, $this->isCli))->exportCards();
    }

    /** 页面键 → 页面实例；未知键落到概览（与拆分前的 default 分支一致）。 */
    private function page(string $page): BasePage
    {
        return match ($page) {
            'settings' => new SettingsPage($this->csrf, $this->isCli),
            'cards' => new CardsPage($this->csrf, $this->isCli),
            'topics' => new TopicsPage($this->csrf, $this->isCli),
            'live' => new LivePage($this->csrf, $this->isCli),
            'push' => new PushPage($this->csrf, $this->isCli),
            'pay' => new PayPage($this->csrf, $this->isCli),
            'withdraw' => new WithdrawPage($this->csrf, $this->isCli),
            'orders' => new OrdersPage($this->csrf, $this->isCli),
            'users' => new UsersPage($this->csrf, $this->isCli),
            default => new DashboardPage($this->csrf, $this->isCli),
        };
    }
}
