<?php
/**
 * 后台页面骨架：导航分组、布局、转义、静态资源地址。
 *
 * 导航按业务分组（首页装修 / 内容 / 交易 / 用户 / 设置），每个页面仍然是
 * 独立的 `?a=` 键——分组只影响外观，不参与路由，也不影响任何写操作。
 *
 * 通用控件在 `Ui/`，页面实现在 `Page/`，样式与脚本在 `assets/`；这里只留
 * 三件全局的东西：layout / e / 资源路径。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin;

final class View
{
    /** 一级导航：分组名 => [页面键 => 组内显示名]。页面键即 `?a=` 的取值。 */
    private const NAV = [
        '概览' => ['dashboard' => '概览'],
        '首页装修' => [
            'banner' => '轮播',
            'featured' => '编辑精选',
            'hot' => '热度榜',
            'guess' => '猜你喜欢',
            'blocks' => '首页专区',
            'links' => '快捷入口',
            'tabs' => '模块与底栏',
        ],
        '内容' => [
            'topics' => '专题',
            'live' => '直播',
            // 推送保留但灰置：客户端只按条数亮红点，还没有内容落地页（见 PushPage 的警示）
            'push' => '推送（灰度）',
        ],
        '交易' => [
            'cards' => '卡密',
            'pay' => '支付',
            'withdraw' => '提现审核',
            'orders' => '订单',
        ],
        '用户' => ['users' => '用户'],
        '设置' => ['settings' => '运营设置'],
    ];

    public static function layout(string $page, string $title, string $html, string $csrf): string
    {
        $activeGroup = self::groupOf($page);
        $tabs = '';
        $subs = '';

        foreach (self::NAV as $group => $pages) {
            $entry = (string)array_key_first($pages);
            $cls = $group === $activeGroup ? ' class="is-active"' : '';
            // 不带 href：带 href 的 <a> 会让浏览器在左下角弹一条目标地址，
            // 而这排标签本来就走 AJAX 换内容（见 assets/admin.js），地址只是降级用的。
            // 去掉 href 后补 tabindex，键盘照样能聚焦、回车/空格照样能切页。
            $tabs .= '<a tabindex="0" data-page="' . self::e($entry)
                . '" data-entry="' . self::e($entry) . '" data-group="' . self::e($group) . '"' . $cls . '>'
                . self::e($group) . '</a>';

            if (count($pages) < 2) {
                continue;
            }
            $links = '';
            foreach ($pages as $key => $label) {
                $on = $key === $page ? ' class="is-active"' : '';
                $links .= '<a tabindex="0" data-page="' . $key
                    . '" data-group="' . self::e($group) . '"' . $on . '>'
                    . self::e($label) . '</a>';
            }
            $hidden = $group === $activeGroup ? '' : ' hidden';
            $subs .= '<div class="sv-sub-list" data-group="' . self::e($group) . '"' . $hidden . '>'
                . $links . '</div>';
        }

        // 当前分组只有一页时（概览 / 用户 / 设置）不占位，否则 tabs 与内容之间会多出一段空白
        $hasSub = count(self::NAV[$activeGroup] ?? []) > 1;
        $subBar = $subs === '' ? '' : '<div class="sv-sub" id="sv-sub"'
            . ($hasSub ? '' : ' hidden') . '>' . $subs . '</div>';

        return '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
            . '<meta name="viewport" content="width=device-width,initial-scale=1">'
            . '<meta name="sv-token" content="' . self::e($csrf) . '">'
            . '<title>星幕 API · ' . self::e($title) . '</title>'
            . '<link rel="stylesheet" href="' . self::asset('admin.css') . '"></head><body>'
            . '<div class="sv-shell">'
            . '<header class="sv-header"><div class="sv-brand">星幕 API'
            . '<span class="sv-version">v' . self::e(\Starveil\Api\Installer::VERSION) . '</span></div>'
            . '</header>'
            . '<nav class="sv-tabs" id="sv-tabs" data-has-sub="' . ($hasSub ? '1' : '0') . '">'
            . $tabs . '</nav>'
            . $subBar
            . '<main class="sv-content" id="sv-content">' . $html . '</main>'
            . '</div><div class="sv-toast" id="sv-toast"></div>'
            . '<div class="sv-progress" id="sv-progress"></div>'
            . '<script src="' . self::asset('admin.js') . '"></script></body></html>';
    }

    public static function e(mixed $value): string
    {
        return htmlspecialchars((string)$value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
    }

    private static function groupOf(string $page): string
    {
        foreach (self::NAV as $group => $pages) {
            if (array_key_exists($page, $pages)) {
                return $group;
            }
        }
        return '概览';
    }

    /**
     * 静态资源地址。样式与脚本放在 `assets/`，用 `?v=filemtime` 做缓存失效。
     *
     * HTTP 下取当前脚本所在目录（插件能装在子目录站点，入口名也能被站长改）；
     * CLI 下没有 web 路径，退回按插件目录名拼，保证同一份代码在哪台机器上都一样。
     */
    private static function asset(string $file): string
    {
        $base = PHP_SAPI === 'cli'
            ? '/addons/' . basename(dirname(__DIR__, 2))
            : rtrim(str_replace('\\', '/', dirname((string)($_SERVER['SCRIPT_NAME'] ?? ''))), '/');
        $path = dirname(__DIR__, 2) . DIRECTORY_SEPARATOR . 'assets' . DIRECTORY_SEPARATOR . $file;
        $version = is_file($path) ? (string)filemtime($path) : '0';
        return self::e($base . '/assets/' . $file . '?v=' . $version);
    }
}
