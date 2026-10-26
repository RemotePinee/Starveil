<?php
/**
 * 订单页：只读列表，取最近 100 条。
 *
 * 概览页的「最近支付订单」也走这里的 table()，两处同一套表头与金额格式。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Installer;
use think\Db;

final class OrdersPage extends BasePage
{
    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '订单', 'html' => $this->orders()];
    }

    /** 订单在客户端/回调里产生，后台只读；保留空实现让页面类形状一致。 */
    public function mutate(array $post): void
    {
    }

    private function orders(): string
    {
        return '<div class="sv-card"><div class="sv-section-title">最近 100 条订单</div>'
            . self::recent(100) . '</div>';
    }

    /** 订单表格本体：概览页只要 8 条，订单页要 100 条。 */
    public static function recent(int $limit): string
    {
        $rows = Db::table(Installer::prefix() . 'cuican_order')->order('order_id desc')->limit($limit)->select();
        $body = '';
        foreach ($rows as $row) {
            $paid = (int)$row['order_status'] === 1;
            $body .= '<tr><td><span class="sv-code">' . self::e($row['order_code']) . '</span></td>'
                . '<td>' . (int)$row['user_id'] . '</td>'
                . '<td>¥' . number_format((float)$row['order_price'], 2, '.', '') . '</td>'
                . '<td>' . ($paid ? '<span class="sv-badge ok">已支付</span>' : '<span class="sv-badge off">未支付</span>') . '</td>'
                . '<td>' . self::e(date('Y-m-d H:i', (int)$row['order_time'])) . '</td></tr>';
        }
        return '<table class="sv-table"><tr><th>订单号</th><th>用户</th><th>金额</th><th>状态</th><th>时间</th></tr>'
            . ($body !== '' ? $body : '<tr><td colspan="5" class="sv-empty">没有订单</td></tr>')
            . '</table>';
    }
}
