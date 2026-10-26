<?php
/**
 * 会员开通：只认「已支付、且还没开通过」的订单。
 *
 * 两条路各自延长会员：支付回调开一次，客户端拿到 `v1.user/group` 再开一次，
 * 买 30 天到账 60 天；而且 upgrade() 压根不看订单，谁调一下都能白拿会员。
 * 现在两个入口共用这里，靠 `cuican_order.opened_at` 的条件更新抢占：
 * 抢到的请求才延长，后来的拿 200 但不加时间。
 */

declare(strict_types=1);

namespace Starveil\Api\Service;

use Starveil\Api\Repo\Orders;
use Starveil\Api\Repo\Users;
use think\Db;

final class Membership
{
    /** 不带 order_code 时，只认这个时间窗内支付的订单（秒）。 */
    private const RECENT_WINDOW = 300;

    public function __construct(
        private Orders $orders = new Orders(),
        private Users $users = new Users(),
    ) {
    }

    /**
     * 客户端开通入口。
     *
     * @return array{code:int,msg:string}
     */
    public function upgrade(int $userId, int $groupId, string $period, ?string $orderCode = null): array
    {
        $order = $this->paidOrder($userId, $orderCode);
        if ($order === null) {
            return ['code' => 400, 'msg' => '未找到已支付的订单'];
        }
        // 同一个订单只开一次：已开通过就直接回 200，客户端只关心"能看了"
        if ((int)($order['opened_at'] ?? 0) !== 0) {
            return ['code' => 200, 'msg' => '会员已开通'];
        }
        $plan = $this->planOf($order, $groupId, $period);
        if ($plan === null) {
            return ['code' => 400, 'msg' => '套餐不存在'];
        }
        try {
            $this->open($order, $plan);
        } catch (\Throwable $e) {
            // 钱已经付了，报错让客户端重试：opened_at 随事务回滚，下次调还能开
            return ['code' => 500, 'msg' => '开通失败，请稍后重试'];
        }
        return ['code' => 200, 'msg' => '会员已开通'];
    }

    /**
     * 支付回调入口：订单已在 notify 里验签并改成已支付，这里只负责按快照开通一次。
     * 抛异常表示这次没开成，由调用方决定给网关回什么。
     */
    public function openByOrder(array $order): void
    {
        if ((string)($order['target_type'] ?? '') !== 'vip') {
            return;
        }
        $plan = $this->planOf($order, 0, '');
        if ($plan === null) {
            return;
        }
        $this->open($order, $plan);
    }

    /**
     * 找一张可开通的订单。
     *
     * 带 order_code 按订单号 + 用户查；不带的只认最近 5 分钟
     * 支付的单——时间窗是给"支付完成跳回 App 再调接口"留的余量，不是给刷单留的。
     * 支付状态与 target_type 在 Repo 的 SQL 里已经过滤，这里不再重复判断。
     */
    private function paidOrder(int $userId, ?string $orderCode): ?array
    {
        $code = trim((string)$orderCode);
        return $code === ''
            ? $this->orders->latestPaidVip($userId, self::RECENT_WINDOW)
            : $this->orders->paidVipByCode($userId, $code);
    }

    /**
     * 天数与会员组以订单快照为准。
     *
     * 快照是下单时写进 `ulog_data` 的 `group_id`/`days`，付款后运营改价也不影响这一单。
     * 客户端传来的 group_id/long 只在快照缺失（手工建单、极旧的订单）时用来回落套餐表，
     * 绝不能让客户端决定开多久——否则 1 天的套餐能换 365 天。
     *
     * @return array{group_id:int,days:int}|null
     */
    private function planOf(array $order, int $clientGroupId, string $clientPeriod): ?array
    {
        $payload = json_decode((string)($order['ulog_data'] ?? ''), true);
        $payload = is_array($payload) ? $payload : [];
        $groupId = (int)($payload['group_id'] ?? 0);
        $days = (int)($payload['days'] ?? 0);
        if ($groupId > 0 && $days > 0) {
            return ['group_id' => $groupId, 'days' => $days];
        }
        return $this->planFromTable((int)($order['target_id'] ?? 0), $clientGroupId, $clientPeriod);
    }

    /** 快照缺失时的兜底：先按客户端传的组 + 周期找，找不到再按订单记的套餐 id 找。 */
    private function planFromTable(int $planId, int $clientGroupId, string $clientPeriod): ?array
    {
        $fallback = null;
        foreach ((new Plans())->all() as $plan) {
            if ($plan['days'] <= 0) {
                continue;
            }
            if ($plan['group_id'] === $clientGroupId && $plan['period'] === $clientPeriod) {
                return ['group_id' => $plan['group_id'], 'days' => $plan['days']];
            }
            if ($fallback === null && $plan['id'] === $planId) {
                $fallback = ['group_id' => $plan['group_id'], 'days' => $plan['days']];
            }
        }
        return $fallback;
    }

    /**
     * 先抢标记再延长时间，两步同一事务。
     *
     * `opened_at` 的条件更新就是锁：两个请求同时进来只有一个 affected=1，
     * 另一个直接返回，不会出现"同一单开两次"。延长失败时整笔回滚，
     * opened_at 一起回到 0，用户可以再试。
     *
     * @param array{group_id:int,days:int} $plan
     */
    private function open(array $order, array $plan): void
    {
        Db::startTrans();
        try {
            if (!$this->orders->claimOpened((int)$order['order_id'])) {
                Db::rollback();
                return;
            }
            $this->users->extendMembership((int)$order['user_id'], $plan['group_id'], $plan['days']);
            Db::commit();
        } catch (\Throwable $e) {
            Db::rollback();
            throw $e;
        }
    }
}
