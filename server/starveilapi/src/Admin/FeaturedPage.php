<?php
/**
 * 后台卡片位页：编辑精选 / 热度榜 / 猜你喜欢。
 *
 * 这几格不按 macCMS 的 `level` 推荐值维护（那样换一部片要改推荐等级），
 * 把它们各落成一组 block：`?a=featured` 维护 position='featured'；
 * 热度榜、猜你喜欢传入 Featured::POSITION_HOT / POSITION_GUESS，
 * 页面逻辑与精选完全一致，只是读写的 position 不同。
 *
 * 与 HomePage 的「首页专区」共用 cuican_block / cuican_block_vod，不新增表；
 * 样式复用 View 的 sv-* 类，输出统一 View::e，写操作由 admin.php 统一校验 CSRF。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin;

use Starveil\Api\Admin\Page\BasePage;
use Starveil\Api\Installer;
use Starveil\Api\Repo\Featured;
use Starveil\Api\Support\Guard;
use think\Db;

final class FeaturedPage extends BasePage
{
    public function __construct(
        string $csrf = '',
        bool $isCli = false,
        private readonly string $position = Featured::POSITION,
        private readonly int $typeId = 0,
        private readonly string $page = 'featured',
    ) {
        parent::__construct($csrf, $isCli);
    }

    /** @return array{title:string,html:string} */
    public function render(): array
    {
        $html = ($this->hasTypeDimension() ? $this->typeCard() : '')
            . $this->groupCard() . $this->addCard() . $this->vodTable();
        $error = trim((string)($_GET['error'] ?? ''));
        if ($error !== '') {
            $html = '<p class="sv-hint sv-flash-err">'
                . htmlspecialchars($error, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8')
                . '</p>' . $html;
        }
        return ['title' => Featured::titleOf($this->position), 'html' => $html];
    }

    /**
     * 校验不通过时回到当前页（带分类参数），一条都不写库。
     *
     * 只有「回哪个 query」是本页特有的，AJAX/JSON 的判定统一交给 [Guard]。
     */
    private function fail(string $message): void
    {
        Guard::fail(
            $this->page,
            $message,
            $this->typeId > 0 ? ['type_id' => $this->typeId] : [],
        );
    }

    /** 是否按分类配置：编辑精选为首页专属，只有热度榜/猜你喜欢需要。 */
    private function hasTypeDimension(): bool
    {
        return $this->position !== Featured::POSITION;
    }

    public function mutate(array $post): void
    {
        match ((string)($post['op'] ?? '')) {
            'save' => $this->saveGroup($post),
            'add' => $this->addVods($post),
            'up' => $this->move((int)($post['id'] ?? 0), -1),
            'down' => $this->move((int)($post['id'] ?? 0), 1),
            'remove' => $this->removeVod((int)($post['id'] ?? 0)),
            default => null,
        };
    }

    // ---- 表单 ----------------------------------------------------------

    /**
     * 分类切换：GET 表单，切完再看/改该分类的组。
     *
     * 热度榜/猜你喜欢都是"每个分类一套"，用 URL 上的 type_id 区分，
     * 不用 JS，也不把状态塞进 session。
     */
    private function typeCard(): string
    {
        return $this->card(
            '配置分类',
            '<form method="get">'
            . '<input type="hidden" name="a" value="' . View::e($this->page) . '">'
            . '<div class="sv-grid">'
            . $this->selectField('分类', 'type_id', $this->typeOptions(), (string)$this->typeId)
            . '</div><div class="sv-actions"><button class="sv-btn" type="submit">切换分类</button></div></form>'
            . '<p class="sv-hint">首页 = 只在首页显示；分类页没单独配时，客户端用该分类'
            . '自己的列表切片，不会拿首页这套。当前：' . View::e($this->typeName()) . '</p>'
        );
    }

    /** @return array<string,string> */
    private function typeOptions(): array
    {
        $out = ['0' => '首页'];
        $rows = Db::name('type')->where('type_pid', 0)->field('type_id,type_name')->order('type_id asc')->select();
        foreach ($rows as $row) {
            $out[(string)(int)$row['type_id']] = (string)$row['type_name'];
        }
        return $out;
    }

    private function typeName(): string
    {
        if (!$this->hasTypeDimension()) {
            return '';
        }
        return $this->typeOptions()[(string)$this->typeId] ?? '首页';
    }

    private function groupCard(): string
    {
        $group = $this->group();
        $title = Featured::titleOf($this->position);

        return $this->card(
            $title . '组' . ($this->hasTypeDimension() ? ' · ' . $this->typeName() : ''),
            '<form method="post">' . $this->csrfField()
            . '<input type="hidden" name="op" value="save"><div class="sv-grid">'
            . $this->field('标题', 'title', (string)($group['title'] ?? $title), '留空则用「' . $title . '」')
            . $this->field('副标题', 'subtitle', (string)($group['subtitle'] ?? ''), '客户端显示在标题下方，可留空')
            . $this->selectField('状态', 'status', ['1' => '启用', '0' => '停用'], (string)($group['status'] ?? '1'))
            . '</div><div class="sv-actions"><button class="sv-btn" type="submit">保存</button></div></form>'
        );
    }

    private function addCard(): string
    {
        $title = Featured::titleOf($this->position);
        $hint = $this->group() === null
            ? '还没有' . $title . '组，加入时会自动创建；多个用英文逗号'
            : '多个用英文逗号；组里已有的会忽略';

        return $this->card(
            '加入影片',
            '<form method="post">' . $this->csrfField()
            . '<input type="hidden" name="op" value="add"><div class="sv-grid">'
            . $this->field('影片 ID', 'vod_ids', '', $hint)
            . '</div><div class="sv-actions"><button class="sv-btn ghost" type="submit">加入'
            . View::e($title) . '</button></div></form>'
        );
    }
    private function vodTable(): string
    {
        $title = Featured::titleOf($this->position);
        $group = $this->group();
        if ($group === null) {
            return '<div class="sv-card"><table class="sv-table"><tr>'
                . '<th>影片 ID</th><th>片名</th><th>排序</th><th>操作</th></tr>'
                . '<tr><td colspan="4" class="sv-empty">还没有' . View::e($title)
                . '组，先在上面保存一次即可创建</td></tr>'
                . '</table></div>';
        }

        $rows = Db::table($this->mapTable())
            ->where('block_id', (int)$group['id'])
            ->order('sort asc,id asc')
            ->select();
        $names = $this->vodNames(array_map(static fn (array $row): int => (int)$row['vod_id'], $rows));

        $body = '';
        $last = count($rows) - 1;
        foreach ($rows as $index => $row) {
            $body .= '<tr><td>' . (int)$row['vod_id'] . '</td>'
                . '<td>' . $this->vodCell((int)$row['vod_id'], $names) . '</td>'
                . '<td>' . (int)$row['sort'] . '</td>'
                . '<td class="sv-inline">'
                . ($index > 0 ? $this->postButton('up', '上移', 'ghost', ['id' => (int)$row['id']]) : '')
                . ($index < $last ? $this->postButton('down', '下移', 'ghost', ['id' => (int)$row['id']]) : '')
                . $this->postButton('remove', '移除', 'danger', ['id' => (int)$row['id']])
                . '</td></tr>';
        }


        return $this->table(['影片 ID', '片名', '排序', '操作'], $body, 4);
    }

    // ---- 写操作 --------------------------------------------------------

    private function saveGroup(array $post): void
    {
        $data = [
            'title' => mb_substr(trim((string)($post['title'] ?? '')), 0, 100),
            'subtitle' => mb_substr(trim((string)($post['subtitle'] ?? '')), 0, 200),
            'status' => (int)($post['status'] ?? 1) === 1 ? 1 : 0,
            'type_id' => $this->typeId,
        ];

        $group = $this->group();
        if ($group !== null) {
            Db::table($this->blockTable())->where('id', (int)$group['id'])->update($data);
            return;
        }

        Db::table($this->blockTable())->insert($data + [
            'layout' => 'row',
            'position' => $this->position,
            'sort' => 0,
            'created_at' => time(),
        ]);
    }

    private function addVods(array $post): void
    {
        $vodIds = [];
        foreach (explode(',', (string)($post['vod_ids'] ?? '')) as $raw) {
            $vodId = (int)trim($raw);
            if ($vodId > 0) {
                $vodIds[$vodId] = $vodId;
            }
        }
        if ($vodIds === []) {
            $this->fail('请填至少一个影片 ID');
        }

        $blockId = $this->groupId();
        if ($blockId <= 0) {
            $this->fail('先选好分类再添加影片');
        }

        // 影片必须真实存在：手输 id 很容易写错，绑上去客户端只显示一张空海报
        $missing = [];
        foreach ($vodIds as $vodId) {
            if (!Db::name('vod')->where('vod_id', $vodId)->find()) {
                $missing[] = $vodId;
            }
        }
        if ($missing !== []) {
            $this->fail('这些影片 id 不存在，已跳过：' . implode('、', $missing));
        }

        $sort = (int)Db::table($this->mapTable())->where('block_id', $blockId)->max('sort') + 1;
        foreach ($vodIds as $vodId) {
            // uk_block_vod 是最终兜底，这里先查一次只是为了让重复提交安静地跳过
            $exists = Db::table($this->mapTable())
                ->where('block_id', $blockId)
                ->where('vod_id', $vodId)
                ->find();
            if ($exists) {
                continue;
            }
            Db::table($this->mapTable())->insert([
                'block_id' => $blockId,
                'vod_id' => $vodId,
                'sort' => $sort++,
            ]);
        }
    }

    private function removeVod(int $id): void
    {
        $group = $this->group();
        if ($group === null || $id <= 0) {
            return;
        }
        Db::table($this->mapTable())
            ->where('id', $id)
            ->where('block_id', (int)$group['id'])
            ->delete();
    }

    /** @param int $delta -1 上移，1 下移 */
    private function move(int $id, int $delta): void
    {
        $group = $this->group();
        if ($group === null || $id <= 0) {
            return;
        }
        $blockId = (int)$group['id'];
        $rows = Db::table($this->mapTable())
            ->where('block_id', $blockId)
            ->order('sort asc,id asc')
            ->select();
        $ids = array_map(static fn (array $row): int => (int)$row['id'], $rows);

        $index = array_search($id, $ids, true);
        $target = $index === false ? -1 : $index + $delta;
        if ($index === false || $target < 0 || $target >= count($ids)) {
            return;
        }
        [$ids[$index], $ids[$target]] = [$ids[$target], $ids[$index]];
        $this->reorder($blockId, $ids);
    }

    /**
     * 整组重排成 0..n-1。
     *
     * 不交换两行的 sort 值：历史数据（导入、早期手工插入）经常整组 sort 都是 0，
     * 交换等于没动；重写一遍顺序最稳，也顺手把空档补齐。
     *
     * @param array<int,int> $ids 期望的行 id 顺序
     */
    private function reorder(int $blockId, array $ids): void
    {
        foreach ($ids as $sort => $rowId) {
            Db::table($this->mapTable())
                ->where('block_id', $blockId)
                ->where('id', $rowId)
                ->update(['sort' => $sort]);
        }
    }

    // ---- 数据 ----------------------------------------------------------

    /** @return array<string,mixed>|null 只维护该 position 下 sort 最小的那条组。 */
    private function group(): ?array
    {
        $row = Db::table($this->blockTable())
            ->where('position', $this->position)
            ->where('type_id', $this->typeId)
            ->order('sort asc,id asc')
            ->find();
        return is_array($row) ? $row : null;
    }

    /** 取该 position 的组 id；不存在时按需创建，保证「加入影片」不会因为找不到组而静默失败。 */
    private function groupId(): int
    {
        $group = $this->group();
        if ($group !== null) {
            return (int)$group['id'];
        }

        Db::table($this->blockTable())->insert([
            'title' => Featured::titleOf($this->position),
            'subtitle' => '',
            'layout' => 'row',
            'position' => $this->position,
            'type_id' => $this->typeId,
            'sort' => 0,
            'status' => 1,
            'created_at' => time(),
        ]);
        // 不依赖 insert 的返回值：刚插进去的这条就是唯一一组，重新查一次最省事
        $created = $this->group();
        return $created === null ? 0 : (int)$created['id'];
    }

    /**
     * @param array<int,int> $vodIds
     * @return array<int,array{name:string,status:int}>
     */
    private function vodNames(array $vodIds): array
    {
        if ($vodIds === []) {
            return [];
        }
        $rows = Db::name('vod')
            ->field('vod_id,vod_name,vod_status')
            ->whereIn('vod_id', array_values(array_unique($vodIds)))
            ->select();

        $out = [];
        foreach ($rows as $row) {
            $out[(int)$row['vod_id']] = [
                'name' => (string)$row['vod_name'],
                'status' => (int)$row['vod_status'],
            ];
        }
        return $out;
    }

    /** @param array<int,array{name:string,status:int}> $names */
    private function vodCell(int $vodId, array $names): string
    {
        $vod = $names[$vodId] ?? null;
        if ($vod === null) {
            return '<span class="sv-badge off">已删除</span> <small>mac_vod 里没有这条，客户端会跳过</small>';
        }
        return View::e($vod['name'])
            . ($vod['status'] === 1
                ? ''
                : ' <span class="sv-badge off">已下架</span> <small>不会出现在客户端</small>');
    }

    private function blockTable(): string
    {
        return Installer::prefix() . 'cuican_block';
    }

    private function mapTable(): string
    {
        return Installer::prefix() . 'cuican_block_vod';
    }

}
