<?php
/**
 * 卡密页：生成、筛选、作废、导出 CSV。
 *
 * 卡表刻意不存 group_id：会员卡兑换统一按设置里的 `vip_group_id` 开通，
 * 两处配置必然漂移，运营改设置即可，历史卡跟着一起生效。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Installer;
use Starveil\Api\Support\Guard;
use Starveil\Api\Support\Setting;
use think\Db;

final class CardsPage extends BasePage
{
    /** 卡密状态筛选：键 → 文案。兑换接口只认 0，2 是后台作废（保留记录可追溯）。 */
    private const FILTERS = [
        'all' => '全部',
        'unused' => '未使用',
        'used' => '已使用',
        'void' => '已作废',
    ];

    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '卡密', 'html' => $this->cards()];
    }

    public function mutate(array $post): void
    {
        // 两种提交：作废单张，或批量生成
        if ((string)($post['op'] ?? '') === 'void') {
            $this->voidCard($post);
            return;
        }
        $this->createCards($post);
    }

    private function cards(): string
    {
        $vipGroup = Setting::int('vip_group_id', 3);
        $form = '<div class="sv-card"><div class="sv-section-title">生成卡密</div><form method="post">'
            . $this->csrfField()
            . '<div class="sv-grid">'
            . $this->field('数量', 'count', '10')
            . $this->selectField('类型', 'type', ['vip' => '会员天数', 'gold' => '金币'])
            . $this->field('面值', 'value', '30', '会员天数或金币数')
            . '</div>'
            // 卡表刻意不存 group_id：会员卡兑换统一按 vip_group_id 开通，
            // 两处配置必然漂移，运营改设置即可，历史卡跟着一起生效。
            . '<p class="sv-hint">会员卡兑换统一开通会员组 id=' . $vipGroup
            . '（运营设置 → 会员组 id）；卡表不单独存组。</p>'
            . '<div class="sv-actions"><button class="sv-btn" type="submit">生成</button></div></form></div>';

        $table = Installer::prefix() . 'cuican_card';
        $status = $this->currentCardStatus();
        $keyword = $this->currentCardKeyword();
        $totals = ['all' => ['全部', (int)Db::table($table)->count()]];
        foreach (self::FILTERS as $key => $label) {
            if ($key !== 'all') {
                $totals[$key] = [$label, $this->cardCount($key)];
            }
        }
        $totals['today'] = ['今日核销', (int)Db::table($table)
            ->where('card_status', 1)
            ->where('used_time', '>=', strtotime('today'))
            ->count()];
        $body = '';
        foreach ($this->cardRows($status, $keyword) as $row) {
            $body .= $this->cardRow($row);
        }
        $table = '<div class="sv-card"><div class="sv-section-title">卡密列表（最近 100 张）</div>'
            . $this->cardFilters($status, $keyword)
            . '<table class="sv-table"><tr><th>卡号</th><th>类型</th><th>面值</th><th>状态</th>'
            . '<th>使用人</th><th>使用时间</th><th></th></tr>'
            . ($body !== '' ? $body : '<tr><td colspan="7" class="sv-empty">还没有卡密</td></tr>')
            . '</table><p class="sv-hint">卡号即卡密，生成后请通过安全渠道发放；已使用的卡不可退回，'
            . '未使用的卡可作废（保留记录，兑换接口只认未使用）。</p></div>';
        return $form . $this->cardStats($totals) . $table;
    }

    /** 顶部统计：全部 / 未使用 / 已使用 / 已作废 / 今日核销（页面展示用，不做对账）。 */
    private function cardStats(array $totals): string
    {
        $html = '<div class="sv-metrics sv-metrics-stacked">';
        foreach ($totals as [$label, $value]) {
            $html .= '<div class="sv-metric"><span>' . self::e($label) . '卡密</span><b>' . $value . '</b></div>';
        }
        return $html . '</div>';
    }

    /** 状态筛选 + 卡号搜索 + 导出，只改 query，不提交表单。 */
    private function cardFilters(string $status, string $keyword): string
    {
        $query = $keyword !== '' ? '&amp;q=' . rawurlencode($keyword) : '';
        $html = '<div class="sv-actions">';
        foreach (self::FILTERS as $key => $label) {
            $class = $key === $status ? 'sv-btn sm' : 'sv-btn ghost sm';
            $html .= '<a class="' . $class . '" href="?a=cards&amp;status=' . $key . $query . '">'
                . self::e($label) . '</a>';
        }
        // 导出沿用当前筛选，做到"看到什么就导出什么"
        $html .= '<a class="sv-btn ghost sm" href="?a=cards&amp;export=1&amp;status=' . $status . $query . '">'
            . '导出 CSV</a></div>';
        // data-plain：View 的 AJAX 只接管后台写表单，GET 搜索表单要放行给浏览器
        return $html . '<form method="get" class="sv-search" data-plain>'
            . '<input type="hidden" name="a" value="cards">'
            . '<input type="hidden" name="status" value="' . $status . '">'
            . '<div class="sv-field"><input type="text" name="q" value="' . self::e($keyword)
            . '" placeholder="按卡号前缀搜索"></div>'
            . '<button class="sv-btn ghost sm" type="submit">搜索</button></form>';
    }

    /** @return array<int,array<string,mixed>> */
    private function cardRows(string $status, string $keyword = '', int $limit = 100): array
    {
        $query = Db::table(Installer::prefix() . 'cuican_card');
        $value = self::cardStatusValue($status);
        if ($value !== null) {
            $query = $query->where('card_status', $value);
        }
        if ($keyword !== '') {
            // 卡号是随机大写十六进制，前缀匹配够用，也不做全表 like '%...%'
            $query = $query->where('card_no', 'like', $keyword . '%');
        }
        return $query->order('card_id desc')->limit($limit)->select();
    }

    /** @param array<string,mixed> $row */
    private function cardRow(array $row): string
    {
        $status = (int)$row['card_status'];
        $badge = $status === 0 ? 'ok' : ($status === 1 ? 'off' : 'warn');
        $usedBy = (int)$row['used_by'];
        $usedTime = (int)($row['used_time'] ?? 0);
        // 作废只出现在未使用行；提交走 POST，View 的 AJAX 会带 CSRF 并刷新本页
        $voidForm = $status === 0
            ? '<form method="post">' . $this->csrfField()
                . '<input type="hidden" name="op" value="void">'
                . '<input type="hidden" name="card_no" value="' . self::e($row['card_no']) . '">'
                . '<button class="sv-btn danger sm" type="submit">作废</button></form>'
            : '';
        return '<tr><td><span class="sv-code">' . self::e($row['card_no']) . '</span></td>'
            . '<td>' . ((string)$row['card_type'] === 'gold' ? '金币' : '会员天数') . '</td>'
            . '<td>' . (int)$row['card_value'] . '</td>'
            . '<td><span class="sv-badge ' . $badge . '">' . self::cardStatusLabel($status) . '</span></td>'
            . '<td>' . ($usedBy > 0 ? $usedBy : '—') . '</td>'
            . '<td>' . ($usedTime > 0 ? date('Y-m-d H:i', $usedTime) : '—') . '</td>'
            . '<td><div class="sv-inline"><button class="sv-btn ghost sm" type="button" data-copy="'
            . self::e($row['card_no']) . '">复制</button>' . $voidForm . '</div></td></tr>';
    }

    private function createCards(array $post): void
    {
        $count = min(max((int)($post['count'] ?? 1), 1), 200);
        $value = max((int)($post['value'] ?? 0), 0);
        $type = (string)($post['type'] ?? 'vip') === 'gold' ? 'gold' : 'vip';
        // 0 面值的卡密兑换不出任何东西，生成出来只是垃圾数据
        if ($value <= 0) {
            Guard::fail('cards', '面值必须大于 0');
        }
        $table = Installer::prefix() . 'cuican_card';
        for ($i = 0; $i < $count; $i++) {
            Db::table($table)->insert([
                'card_no' => strtoupper(bin2hex(random_bytes(8))),
                'card_pwd' => '',
                'card_type' => $type,
                'card_value' => $value,
                'card_status' => 0,
            ]);
        }
    }

    /** 状态筛选键 → card_status 值；`all` 不筛，返回 null。 */
    private static function cardStatusValue(string $status): ?int
    {
        return ['unused' => 0, 'used' => 1, 'void' => 2][$status] ?? null;
    }

    private static function cardStatusLabel(int $status): string
    {
        return match ($status) {
            1 => '已使用',
            2 => '已作废',
            default => '未使用',
        };
    }

    private function cardCount(string $status): int
    {
        $query = Db::table(Installer::prefix() . 'cuican_card');
        $value = self::cardStatusValue($status);
        return (int)($value === null ? $query->count() : $query->where('card_status', $value)->count());
    }

    /** 当前筛选键；CLI 自检（php admin.php cards）没有 query，按"全部"处理。 */
    private function currentCardStatus(): string
    {
        $status = $this->isCli ? 'all' : (string)($_GET['status'] ?? 'all');
        return isset(self::FILTERS[$status]) ? $status : 'all';
    }

    /** 卡号搜索词：只留字母数字并转大写，避免把 % 之类带进 LIKE。 */
    private function currentCardKeyword(): string
    {
        $raw = $this->isCli ? '' : (string)($_GET['q'] ?? '');
        return strtoupper((string)preg_replace('/[^0-9A-Za-z]/', '', $raw));
    }

    /**
     * 作废未使用的卡。
     *
     * `card_status=2` 保留记录可追溯，兑换接口只认 0，所以作废即失效。
     * where 里带 `card_status=0`，与兑换的 0→1 原子更新互斥，不会作废刚被兑换的卡。
     *
     * @param array<string,mixed> $post
     */
    private function voidCard(array $post): void
    {
        $code = strtoupper(trim((string)($post['card_no'] ?? '')));
        if ($code === '') {
            return;
        }
        Db::table(Installer::prefix() . 'cuican_card')
            ->where('card_no', $code)
            ->where('card_status', 0)
            ->update(['card_status' => 2]);
    }

    /**
     * 导出当前筛选的卡密为 CSV。
     *
     * HTTP 下自己发头并直接写输出流，不走 [View::layout]；带 UTF-8 BOM 让 Excel
     * 认出中文，每个字段都用双引号包裹、内部引号翻倍。
     */
    public function exportCards(): void
    {
        // 导出量可控（人工触发），上限防止一次拉爆内存
        $rows = $this->cardRows($this->currentCardStatus(), $this->currentCardKeyword(), 5000);
        $names = $this->cardUserNames($rows);
        if (!$this->isCli) {
            header('Content-Type: text/csv; charset=utf-8');
            header('Content-Disposition: attachment; filename="cards-' . date('Ymd') . '.csv"');
            header('Cache-Control: no-store');
        }
        $out = fopen('php://output', 'wb');
        if ($out === false) {
            // 输出流打不开时后台没有第二条通道，只能返回空响应
            return;
        }
        fwrite($out, "\xEF\xBB\xBF");
        $this->csvLine($out, ['卡号', '类型', '面值', '状态', '使用人', '使用时间']);
        foreach ($rows as $row) {
            $usedBy = (int)$row['used_by'];
            $usedTime = (int)($row['used_time'] ?? 0);
            $this->csvLine($out, [
                (string)$row['card_no'],
                (string)$row['card_type'] === 'gold' ? '金币' : '会员天数',
                (string)(int)$row['card_value'],
                self::cardStatusLabel((int)$row['card_status']),
                $usedBy > 0 ? ($names[$usedBy] ?? ('用户#' . $usedBy)) : '',
                $usedTime > 0 ? date('Y-m-d H:i:s', $usedTime) : '',
            ]);
        }
        fclose($out);
    }

    /**
     * 导出时把 used_by 换成用户名；查不到（用户已注销）就回退成用户 id。
     *
     * @param array<int,array<string,mixed>> $rows
     * @return array<int,string>
     */
    private function cardUserNames(array $rows): array
    {
        $ids = [];
        foreach ($rows as $row) {
            $id = (int)($row['used_by'] ?? 0);
            if ($id > 0) {
                $ids[$id] = $id;
            }
        }
        if ($ids === []) {
            return [];
        }
        $names = [];
        $users = Db::name('user')->where('user_id', 'in', array_values($ids))->field('user_id,user_name')->select();
        foreach ($users as $user) {
            $names[(int)$user['user_id']] = (string)$user['user_name'];
        }
        return $names;
    }

    /** 写一行 CSV：所有字段双引号包裹，内部引号翻倍；CRLF 兼容 Excel。 @param resource $out */
    private function csvLine($out, array $fields): void
    {
        $cells = [];
        foreach ($fields as $field) {
            $cells[] = '"' . str_replace('"', '""', (string)$field) . '"';
        }
        fwrite($out, implode(',', $cells) . "\r\n");
    }
}
