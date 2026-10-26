<?php
/**
 * 会员价目表。
 *
 * 单独一个类是因为这张表有三个读者：下单时按价格反查套餐、开通时快照缺失要回落到套餐表、
 * 后台展示。Payment 已经贴着 450 行上限，"卖什么价"与"怎么收钱"本来就是两件事。
 */

declare(strict_types=1);

namespace Starveil\Api\Service;

use Starveil\Api\Support\Setting;
use think\Db;

final class Plans
{
    /**
     * 价目表。
     *
     * 优先用 setting 里的 `vip_plans`；为空时回退到 macCMS 会员组自带的
     * `group_points_day/week/month/year`（历史插件就是除以 10 当元用的），
     * 这样后台不用两处维护。
     *
     * @return array<int,array{id:int,group_id:int,period:string,days:int,price_yuan:float}>
     */
    public function all(): array
    {
        // 会员体系关闭时不再下发任何套餐：这是"停止售卖"的唯一入口，
        // createOrder 也在这里反查套餐，所以同时挡住了直接按价格下单。
        if (Setting::int('member_enabled', 1) !== 1) {
            return [];
        }
        $raw = Setting::json('vip_plans', []);
        if ($raw === []) {
            return $this->fromGroups();
        }
        $out = [];
        foreach ($raw as $index => $plan) {
            $out[] = [
                'id' => (int)($plan['id'] ?? $index + 1),
                'group_id' => (int)($plan['group_id'] ?? Setting::int('vip_group_id', 3)),
                'period' => (string)($plan['period'] ?? 'month'),
                'days' => (int)($plan['days'] ?? 30),
                'price_yuan' => (float)($plan['price_yuan'] ?? 0),
            ];
        }
        return $out;
    }

    /** @return array<int,array{id:int,group_id:int,period:string,days:int,price_yuan:float}> */
    private function fromGroups(): array
    {
        $map = [
            'day' => ['column' => 'group_points_day', 'days' => 1],
            'week' => ['column' => 'group_points_week', 'days' => 7],
            'month' => ['column' => 'group_points_month', 'days' => 30],
            'year' => ['column' => 'group_points_year', 'days' => 365],
        ];
        $groups = Db::name('group')->where('group_status', 1)->where('group_id', '>', 1)->select();
        $out = [];
        $id = 1;
        foreach ($groups as $group) {
            foreach ($map as $period => $meta) {
                $points = (int)($group[$meta['column']] ?? 0);
                if ($points <= 0) {
                    continue;
                }
                $out[] = [
                    'id' => $id++,
                    'group_id' => (int)$group['group_id'],
                    'period' => $period,
                    'days' => $meta['days'],
                    'price_yuan' => round($points / 10, 2),
                ];
            }
        }
        return $out;
    }
}
