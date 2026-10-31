<?php
/**
 * 提现审核页：最近 100 条申请，待审核的可通过或拒绝。
 *
 * 拒绝会把金币按 `gold_per_yuan` 退回（见 withdrawAction）；通过只改状态，打款在站外完成。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Installer;
use Starveil\Api\Repo\Users;
use Starveil\Api\Support\Setting;
use think\Db;

final class WithdrawPage extends BasePage
{
    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '提现审核', 'html' => $this->withdraw()];
    }

    public function mutate(array $post): void
    {
        $this->withdrawAction($post);
    }

    private function withdraw(): string
    {
        $rows = Db::table(Installer::prefix() . 'cuican_gold_withdraw')->order('w_id desc')->limit(100)->select();
        $statuses = [0 => ['待审核', 'warn'], 1 => ['已通过', 'ok'], 2 => ['已拒绝', 'off']];
        $body = '';
        foreach ($rows as $row) {
            [$label, $class] = $statuses[(int)$row['status']] ?? ['未知', 'off'];
            // 未迁移的老库里没有这一列，取不到就当没传，后台照旧只显示账号姓名
            $qrcode = trim((string)($row['qrcode'] ?? ''));
            $thumb = $qrcode === '' ? '' : '<a href="' . self::e($qrcode) . '" target="_blank" rel="noopener">'
                . '<img class="sv-thumb" src="' . self::e($qrcode) . '" alt="收款码"></a>';
            $body .= '<tr><td>' . (int)$row['w_id'] . '</td><td>' . (int)$row['user_id'] . '</td>'
                . '<td>' . (int)$row['amount'] . ' 元</td>'
                . '<td>' . self::e($row['account']) . '<br><small>' . self::e($row['realname']) . '</small>'
                . $thumb . '</td>'
                . '<td><span class="sv-badge ' . $class . '">' . $label . '</span></td><td>';
            if ((int)$row['status'] === 0) {
                $body .= '<form method="post" class="sv-inline">' . $this->csrfField()
                    . '<input type="hidden" name="w_id" value="' . (int)$row['w_id'] . '">'
                    . '<button class="sv-btn sm" name="op" value="approve" type="submit">通过</button>'
                    . '<button class="sv-btn danger sm" name="op" value="reject" type="submit">拒绝</button></form>';
            }
            $body .= '</td></tr>';
        }
        return '<div class="sv-card"><div class="sv-section-title">提现申请</div>'
            . '<table class="sv-table"><tr><th>id</th><th>用户</th><th>金额</th><th>收款信息</th><th>状态</th><th></th></tr>'
            . ($body !== '' ? $body : '<tr><td colspan="6" class="sv-empty">没有提现申请</td></tr>')
            . '</table><p class="sv-hint">拒绝会按申请时实扣的金币原额退回；通过只改状态，打款需手动完成。</p></div>';
    }

    /**
     * 通过 / 拒绝。
     *
     * 两个操作都先做「待审核 → 已处理」的条件状态迁移，再动钱，且在同一事务里。
     * 是先退款再改状态、两步都没有条件：两个管理员同时点「拒绝」，
     * 两次 addGold 都会执行，金币退两遍。现在只有 affected=1 的那个请求能走到退款。
     */
    private function withdrawAction(array $post): void
    {
        $id = (int)($post['w_id'] ?? 0);
        $op = (string)($post['op'] ?? '');
        if ($op !== 'approve' && $op !== 'reject') {
            return;
        }
        $table = Installer::prefix() . 'cuican_gold_withdraw';
        $row = Db::table($table)->where('w_id', $id)->find();
        if (!$row || (int)$row['status'] !== 0) {
            return;
        }
        Db::startTrans();
        try {
            $moved = Db::table($table)
                ->where('w_id', $id)
                ->where('status', 0)
                ->update(['status' => $op === 'approve' ? 1 : 2, 'handled_at' => time()]);
            if ($moved === 0) {
                // 别人已经处理过这一单：什么都不做，也不退款
                Db::rollback();
                return;
            }
            if ($op === 'reject') {
                $this->refundGold($row);
            }
            Db::commit();
        } catch (\Throwable $e) {
            // 退款失败时状态一起回滚，申请重新回到待审核，管理员可以再点一次
            Db::rollback();
            throw $e;
        }
    }

    /**
     * 拒绝提现时退金币。
     *
     * `gold_cost` 是申请时实扣的金币快照，按它原额退回；不能再乘「当前」比例——
     * 运营把 `gold_per_yuan` 从 10 调到 100 后批量拒绝历史申请，旧算法每单退 10 倍。
     *
     * 加列之前的老申请没有 `gold_cost`（0），只能按当时的口径重算：退回 0 金币
     * 等于把用户的钱吞掉，比按错比例退更糟。
     */
    private function refundGold(array $row): void
    {
        $cost = (int)($row['gold_cost'] ?? 0);
        if ($cost <= 0) {
            $cost = (int)$row['amount'] * Setting::int('gold_per_yuan', 10);
        }
        if ($cost > 0) {
            (new Users())->addGold((int)$row['user_id'], $cost);
        }
    }
}
