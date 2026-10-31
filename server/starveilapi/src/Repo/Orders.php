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
    /**
     * `episode_number` 是 v2.6.0 才加的列：迁移没跑过的库里查它会报 1054 Unknown column，
     * 把整个试看接口打成 500。查一次记住，缺列就退回按整部判。
     */
    private static ?bool $episodeColumn = null;

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
        int $episodeNumber = 0,
    ): int {
        $row = [
            'order_code' => $code,
            'user_id' => $userId,
            'order_price' => $price,
            'order_status' => 0,
            'order_pay_type' => $channel,
            'order_time' => time(),
            'target_type' => $targetType,
            'target_id' => $targetId,
            'ulog_data' => json_encode($payload, JSON_UNESCAPED_UNICODE),
        ];
        // 只在列存在时写：没迁移的老库插这一列是 1064，会把下单整条打挂
        if ($this->hasEpisodeColumn()) {
            $row['episode_number'] = max($episodeNumber, 0);
        }
        return (int)Db::table($this->table())->insertGetId($row);
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

    /**
     * 这一集是否已经买过。
     *
     * 只按剧 id 判时买一集解锁整部；`$episodeNumber > 0` 时按集号精确匹配。
     * 加列之前的老单没有集号（0），它们当初确实整部解锁过，按集号严格判会把
     * 已付费用户挡在门外，所以 0 仍然放行——历史数据的固有边界，新单不受影响。
     */
    public function hasPaidEpisode(int $userId, int $vodId, int $episodeNumber = 0): bool
    {
        $query = Db::table($this->table())
            ->where('user_id', $userId)
            ->where('target_type', 'episode')
            ->where('target_id', $vodId)
            ->where('order_status', 1);
        if ($episodeNumber > 0 && $this->hasEpisodeColumn()) {
            // 拼进 IN 的两个值都是 int，不涉及用户输入
            $query->where('episode_number', 'in', $episodeNumber . ',0');
        }
        return (bool)$query->find();
    }

    private function hasEpisodeColumn(): bool
    {
        if (self::$episodeColumn !== null) {
            return self::$episodeColumn;
        }
        try {
            $rows = Db::query('SHOW COLUMNS FROM `' . $this->table() . "` LIKE 'episode_number'");
        } catch (\Throwable $e) {
            // 表都还没建起来：当成没有这一列，让调用方走老口径，不在这里抛
            return self::$episodeColumn = false;
        }
        return self::$episodeColumn = is_array($rows) && $rows !== [];
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
