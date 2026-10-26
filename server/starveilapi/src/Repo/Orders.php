<?php
/**
 * 订单数据访问。
 *
 * 状态机只有一个方向：0（未支付）→ 1（已支付）。在回调里用
 * `where('order_status',1)` 查订单再更新成 1，逻辑正好写反；
 * 所有状态迁移都带 `where('order_status',0)`，重复回调天然幂等。
 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

use Starveil\Api\Installer;
use think\Db;

final class Orders
{
    private function table(): string
    {
        return Installer::prefix() . 'cuican_order';
    }

    public function create(
        int $userId,
        string $code,
        float $price,
        string $channel,
        string $targetType,
        int $targetId,
        array $payload = [],
    ): int {
        return (int)Db::table($this->table())->insertGetId([
            'order_code' => $code,
            'user_id' => $userId,
            'order_price' => $price,
            'order_status' => 0,
            'order_pay_type' => $channel,
            'order_time' => time(),
            'target_type' => $targetType,
            'target_id' => $targetId,
            'ulog_data' => json_encode($payload, JSON_UNESCAPED_UNICODE),
        ]);
    }

    public function byCode(string $code): ?array
    {
        if ($code === '') {
            return null;
        }
        $row = Db::table($this->table())->where('order_code', $code)->find();
        return $row ?: null;
    }

    /**
     * 标记已支付。返回 true 表示"本次确实完成了状态迁移"，
     * false 表示订单不存在或已支付过（重复回调）。
     */
    public function markPaid(string $code, float $paidAmount): bool
    {
        $row = $this->byCode($code);
        if (!$row || (int)$row['order_status'] !== 0) {
            return false;
        }
        // 金额必须与下单金额一致，完全不看回调金额
        if (abs((float)$row['order_price'] - $paidAmount) > 0.001) {
            return false;
        }
        $affected = Db::table($this->table())
            ->where('order_code', $code)
            ->where('order_status', 0)
            ->update(['order_status' => 1, 'order_pay_time' => time()]);
        return $affected > 0;
    }

    public function hasPaidEpisode(int $userId, int $vodId): bool
    {
        $row = Db::table($this->table())
            ->where('user_id', $userId)
            ->where('target_type', 'episode')
            ->where('target_id', $vodId)
            ->where('order_status', 1)
            ->find();
        return (bool)$row;
    }

    /**
     * 抢「已开通」标记。返回 true 表示本次抢到，可以延长会员；
     * false 表示这单已经被开通过——条件更新本身就是锁，不需要另外加锁。
     */
    public function claimOpened(int $orderId): bool
    {
        $affected = Db::table($this->table())
            ->where('order_id', $orderId)
            ->where('opened_at', 0)
            ->update(['opened_at' => time()]);
        return $affected > 0;
    }

    /**
     * 按订单号取「已支付 + 会员」订单，且必须属于本人。
     *
     * 状态与类型直接写进 WHERE：拿别人的订单号、拿未支付或单集订单来调开通接口，
     * 查出来就是空的，不会被上层误当成本人已付款。
     */
    public function paidVipByCode(int $userId, string $code): ?array
    {
        if ($code === '') {
            return null;
        }
        $row = Db::table($this->table())
            ->where('order_code', $code)
            ->where('user_id', $userId)
            ->where('order_status', 1)
            ->where('target_type', 'vip')
            ->find();
        return $row ?: null;
    }

    /** 最近 N 秒内支付的会员订单（不带 order_code 时用）。 */
    public function latestPaidVip(int $userId, int $seconds): ?array
    {
        $row = Db::table($this->table())
            ->where('user_id', $userId)
            ->where('order_status', 1)
            ->where('target_type', 'vip')
            ->where('order_time', '>=', time() - max($seconds, 0))
            ->order('order_id desc')
            ->find();
        return $row ?: null;
    }
}
