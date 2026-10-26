<?php
/**
 * 概览页：几项关键指标 + 最近支付订单，只读，没有任何写操作。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Installer;
use think\Db;

final class DashboardPage extends BasePage
{
    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '概览', 'html' => $this->dashboard()];
    }

    /** 概览页没有提交入口；保留空实现是为了让所有页面类形状一致。 */
    public function mutate(array $post): void
    {
    }

    private function dashboard(): string
    {
        $p = Installer::prefix();
        $metrics = [
            ['注册用户', (int)Db::name('user')->count()],
            ['已支付订单', (int)Db::table($p . 'cuican_order')->where('order_status', 1)->count()],
            ['待审核提现', (int)Db::table($p . 'cuican_gold_withdraw')->where('status', 0)->count()],
            ['未使用卡密', (int)Db::table($p . 'cuican_card')->where('card_status', 0)->count()],
            // 弹幕库在 CMS 自己表里（App 与网页共用），不是插件的 cuican_* 表
            ['弹幕条数', (int)Db::name('danmaku')->count()],
        ];
        $html = '<div class="sv-metrics">';
        foreach ($metrics as [$label, $value]) {
            $html .= '<div class="sv-metric"><span>' . self::e($label) . '</span><b>' . $value . '</b></div>';
        }
        $html .= '</div>';
        $html .= '<div class="sv-card"><div class="sv-section-title">最近支付订单</div>'
            . OrdersPage::recent(8) . '</div>';
        $html .= '<p class="sv-hint">播放解析在「运营设置 → 播放解析」，不填客户端无法播放；'
            . '支付通道在「交易 → 支付」配置，回调地址那里直接复制。</p>';
        return $html;
    }
}
