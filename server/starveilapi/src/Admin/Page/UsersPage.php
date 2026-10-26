<?php
/**
 * 用户页：按账号/昵称搜，最多 50 条，行内可直接加减积分与金币。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Repo\Users;
use think\Db;

final class UsersPage extends BasePage
{
    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '用户', 'html' => $this->users()];
    }

    public function mutate(array $post): void
    {
        $this->userAction($post);
    }

    private function users(): string
    {
        $keyword = trim((string)($_GET['kw'] ?? ''));
        $query = Db::name('user');
        if ($keyword !== '') {
            $query->whereLike('user_name', '%' . $keyword . '%');
        }
        $rows = $query->field('user_id,user_name,user_nick_name,group_id,user_points,user_end_time')
            ->order('user_id desc')->limit(50)->select();

        $search = '<div class="sv-card"><form method="get" class="sv-inline" data-plain>'
            . '<input type="hidden" name="a" value="users">'
            . '<input class="sv-input" name="kw" value="' . self::e($keyword)
            . '" placeholder="账号 / 昵称">'
            . '<button class="sv-btn ghost" type="submit">搜索</button>'
            . '<span class="sv-hint sv-hint-inline">按账号或昵称模糊查，最多返回 50 条</span>'
            . '</form></div>';

        $body = '';
        foreach ($rows as $row) {
            $expire = (int)$row['user_end_time'] > time() ? date('Y-m-d', (int)$row['user_end_time']) : '-';
            $body .= '<tr><td>' . (int)$row['user_id'] . '</td><td>' . self::e($row['user_name'])
                . '<br><small>' . self::e($row['user_nick_name']) . '</small></td>'
                . '<td>' . self::e($row['group_id']) . '</td><td>' . (int)$row['user_points'] . '</td>'
                . '<td>' . $expire . '</td><td><form method="post" class="sv-inline">' . $this->csrfField()
                . '<input type="hidden" name="op" value="adjust"><input type="hidden" name="user_id" value="' . (int)$row['user_id'] . '">'
                . '<input name="points" placeholder="积分 +/-" class="sv-adjust-input">'
                . '<input name="gold" placeholder="金币 +/-" class="sv-adjust-input">'
                . '<button class="sv-btn sm" type="submit">调整</button></form></td></tr>';
        }
        $table = '<div class="sv-card"><div class="sv-section-title">最近 50 个用户</div>'
            . '<table class="sv-table"><tr><th>id</th><th>账号</th><th>组</th><th>积分</th><th>会员到期</th><th></th></tr>'
            . ($body !== '' ? $body : '<tr><td colspan="6" class="sv-empty">没有匹配用户</td></tr>')
            . '</table></div>';
        return $search . $table;
    }

    private function userAction(array $post): void
    {
        if ((string)($post['op'] ?? '') !== 'adjust') {
            return;
        }
        $userId = (int)($post['user_id'] ?? 0);
        if ($userId <= 0) {
            return;
        }
        $users = new Users();
        $points = (int)($post['points'] ?? 0);
        $gold = (int)($post['gold'] ?? 0);
        if ($points !== 0) {
            $users->addPoints($userId, $points);
        }
        if ($gold !== 0) {
            $users->addGold($userId, $gold);
        }
    }
}
