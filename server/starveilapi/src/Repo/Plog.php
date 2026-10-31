<?php
/**
 * `mac_plog` 积分日志。
 *
 * 为什么必须写：PC 站后台的「积分日志」、分销结算、会员过期清洗都以这张表为账本。
 * 插件只 `UPDATE mac_user.user_points` 而不落日志时，后台看到的余额没有明细可对，
 * 分销佣金也算不出来——同一个用户在 App 和 PC 站的账目是两套。
 *
 * 列名与类型码来自 macCMS 核心源码（本仓库不含核心，核对的是上游 master）：
 * - 列：`application/install/sql/install.sql` 的 `mac_plog`
 *   （`user_id` / `plog_type` / `plog_points` / `plog_time` / `plog_remarks`，
 *   `plog_points` 是 smallint，`plog_remarks` varchar(100)）；
 * - 类型码：`application/common.php` 的 `mac_get_plog_type_text()`，
 *   收入段 1-6/10/11/14/15、支出段 7-9/12/13（`index/controller/User.php::plog()`）。
 */

declare(strict_types=1);

namespace Starveil\Api\Repo;

use think\Db;

final class Plog
{
    /** 1 积分充值。后台手动加积分归到这一类。 */
    public const TYPE_RECHARGE = 1;

    /** 7 积分升级会员。支出段，核心 `model/User` 开通会员时用的就是它。 */
    public const TYPE_UPGRADE = 7;

    /** 8 积分消费。核心扣分买内容（buy_popedom）用它。 */
    public const TYPE_CONSUME = 8;

    /** 11 任务 / 签到奖励。签到、每日任务、观看时长奖励都归这一类。 */
    public const TYPE_TASK = 11;

    /**
     * 记一条积分日志。
     *
     * `$points` 记绝对值：方向由 `plog_type` 决定（支出段 7-9/12/13），与核心写法一致，
     * 后台「收入 / 支出」两个筛选才分得开。
     */
    public function record(int $userId, int $type, int $points, string $remarks = ''): void
    {
        if ($userId <= 0 || $type < 1 || $type > 15) {
            return;
        }
        try {
            Db::name('plog')->insert([
                'user_id' => $userId,
                'plog_type' => $type,
                'plog_points' => $points,
                'plog_time' => time(),
                'plog_remarks' => mb_substr($remarks, 0, 100),
            ]);
        } catch (\Throwable $e) {
            // 积分已经落库，日志写失败不能反过来让用户看到 500：余额是主数据，
            // 日志只是对账凭据，这里留痕给运维排查，不阻断业务。
            error_log('[starveil] 写 mac_plog 失败 uid=' . $userId . ' type=' . $type . ' err=' . $e->getMessage());
        }
    }
}
