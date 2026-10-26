<?php
/**
 * 专题页：专题增删改 + 绑定影片 + 每条绑定影片的上下移动/移除。
 *
 * 绑定这里是三重校验：专题必须存在、影片必须在 `mac_vod` 里存在、重复绑定跳过；
 * 三条都不静默吞掉——手输 id 写错时运营必须看到原因。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Installer;
use Starveil\Api\Support\Guard;
use Starveil\Api\Support\Reorder;
use think\Db;

final class TopicsPage extends BasePage
{
    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '专题', 'html' => $this->topics()];
    }

    public function mutate(array $post): void
    {
        $this->topicAction($post);
    }

    private function topics(): string
    {
        $prefix = Installer::prefix();
        $editId = (int)($_GET['edit'] ?? 0);
        $editing = $editId > 0
            ? Db::table($prefix . 'cuican_topic')->where('topic_id', $editId)->find()
            : null;

        $form = '<div class="sv-card"><div class="sv-section-title">'
            . ($editing ? '编辑专题 #' . $editId : '新建专题') . '</div><form method="post">'
            . $this->csrfField()
            . '<input type="hidden" name="op" value="' . ($editing ? 'save' : 'create') . '">'
            . ($editing ? '<input type="hidden" name="topic_id" value="' . $editId . '">' : '')
            . '<div class="sv-grid">'
            . $this->field('名称', 'name', (string)($editing['name'] ?? ''))
            . $this->field('封面地址', 'pic', (string)($editing['pic'] ?? ''))
            . $this->field('简介', 'blurb', (string)($editing['blurb'] ?? ''))
            . $this->field('排序', 'sort', (string)($editing['sort'] ?? '0'), '越小越前')
            . $this->selectField(
                '状态',
                'status',
                ['1' => '启用', '0' => '停用'],
                (string)($editing['status'] ?? '1')
            )
            . '</div><div class="sv-actions"><button class="sv-btn" type="submit">'
            . ($editing ? '保存' : '新建') . '</button>'
            . ($editing ? '<a class="sv-btn ghost" href="?a=topics">取消编辑</a>' : '')
            . '</div></form></div>';

        $bind = '<div class="sv-card"><div class="sv-section-title">绑定影片</div><form method="post">'
            . $this->csrfField() . '<input type="hidden" name="op" value="bind"><div class="sv-grid">'
            . $this->field('专题 id', 'topic_id')
            . $this->field('影片 id', 'vod_ids', '', '多个用英文逗号')
            . '</div><div class="sv-actions"><button class="sv-btn ghost" type="submit">绑定</button></div></form>'
            . '<p class="sv-hint">绑定后可在下方专题里逐条「上移」「下移」调序，或「移除」取消绑定。</p></div>';

        $rows = Db::table($prefix . 'cuican_topic')->order('sort asc,topic_id asc')->select();
        $films = $this->topicFilms($rows);
        $cards = '';
        foreach ($rows as $row) {
            $topicId = (int)$row['topic_id'];
            $blurb = (string)$row['blurb'];
            // 标题与元信息分两端：原来全挤在一行，长名字会把「排序」「启用」推得散开
            $cards .= '<div class="sv-card">'
                . '<div class="sv-split">'
                . '<div class="sv-section-title sv-title-flush">#'
                . $topicId . ' ' . self::e($row['name']) . '</div>'
                . '<div class="sv-inline">'
                . '<span class="sv-hint sv-hint-inline">排序 ' . (int)$row['sort'] . '</span>'
                . ((int)$row['status'] === 1
                    ? '<span class="sv-badge ok">启用</span>'
                    : '<span class="sv-badge off">停用</span>')
                . '</div></div>'
                . '<p class="sv-hint sv-blurb">'
                . self::e($blurb !== '' ? $blurb : '（无简介）') . '</p>'
                . '<div class="sv-inline">'
                . '<a class="sv-btn ghost sm" href="?a=topics&edit=' . $topicId . '">编辑</a>'
                . $this->postButton('move_topic', '上移', 'ghost', ['topic_id' => $topicId, 'dir' => 'up'])
                . $this->postButton('move_topic', '下移', 'ghost', ['topic_id' => $topicId, 'dir' => 'down'])
                . $this->postButton('delete', '删除', 'danger', ['topic_id' => $topicId])
                . '</div>' . $this->filmTable($topicId, $films[$topicId] ?? []) . '</div>';
        }
        if ($cards === '') {
            $cards = '<div class="sv-card"><p class="sv-empty">还没有专题</p></div>';
        }
        // 绑定结果直接摆在页头：输错 id 时如果没提示，运营会以为"点了没反应"
        $flash = '';
        $errorMsg = trim((string)($_GET['error'] ?? ''));
        $noticeMsg = trim((string)($_GET['notice'] ?? ''));
        if ($errorMsg !== '') {
            $flash = '<p class="sv-hint sv-flash-err">' . self::e($errorMsg) . '</p>';
        } elseif ($noticeMsg !== '') {
            $flash = '<p class="sv-hint sv-flash-ok">' . self::e($noticeMsg) . '</p>';
        }
        return $flash . $form . $bind . '<div class="sv-section-title">专题列表</div>' . $cards;
    }

    /**
     * 一次取出所有专题的绑定影片，避免逐专题查询。
     *
     * @param array<int,array<string,mixed>> $topics
     * @return array<int,array<int,array<string,mixed>>>
     */
    private function topicFilms(array $topics): array
    {
        $topicIds = [];
        foreach ($topics as $topic) {
            $topicIds[] = (int)$topic['topic_id'];
        }
        if ($topicIds === []) {
            return [];
        }
        $rows = Db::table(Installer::prefix() . 'cuican_topic_vod')
            ->whereIn('topic_id', $topicIds)
            ->order('sort asc,vod_id asc')
            ->select();
        $map = [];
        foreach ($rows as $row) {
            $map[(int)$row['topic_id']][] = $row;
        }
        return $map;
    }

    /**
     * 专题下已绑定影片：vod_id + 片名（mac_vod），每行可上移/下移/移除。
     *
     * @param array<int,array<string,mixed>> $films
     */
    private function filmTable(int $topicId, array $films): string
    {
        // 空态共用同一条分割线（.sv-film-box），视觉上跟"有影片"时一致，不会突然少一块
        if ($films === []) {
            return '<p class="sv-hint sv-film-box">已绑定影片（0 部）· 在上方「绑定影片」里填影片 id</p>';
        }
        $vodIds = [];
        foreach ($films as $film) {
            $vodIds[] = (int)$film['vod_id'];
        }
        $names = Db::name('vod')->whereIn('vod_id', $vodIds)->column('vod_name', 'vod_id');
        $body = '';
        foreach ($films as $film) {
            $vodId = (int)$film['vod_id'];
            $label = array_key_exists($vodId, $names)
                ? ((string)$names[$vodId] !== '' ? (string)$names[$vodId] : '（无片名）')
                : '（影片不存在）';
            $body .= '<tr><td>' . $vodId . '</td><td>' . self::e($label) . '</td>'
                . '<td>' . (int)$film['sort'] . '</td><td><div class="sv-inline">'
                . $this->postButton(
                    'move_vod',
                    '上移',
                    'ghost',
                    ['topic_id' => $topicId, 'vod_id' => $vodId, 'dir' => 'up']
                )
                . $this->postButton(
                    'move_vod',
                    '下移',
                    'ghost',
                    ['topic_id' => $topicId, 'vod_id' => $vodId, 'dir' => 'down']
                )
                . $this->postButton(
                    'unbind',
                    '移除',
                    'danger',
                    ['topic_id' => $topicId, 'vod_id' => $vodId]
                )
                . '</div></td></tr>';
        }
        // summary 用小号灰字：卡片标题是「#2 测试」，这里再顶一个同款粗标题会打乱层级
        return '<details open class="sv-film-box">'
            . '<summary>'
            . '已绑定影片（' . count($films) . ' 部）</summary>'
            . '<table class="sv-table sv-table-films sv-film-table">'
            . '<tr><th>vod_id</th><th>片名</th><th>排序</th>'
            . '<th></th></tr>'
            . $body . '</table></details>';
    }

    private function topicAction(array $post): void
    {
        $prefix = Installer::prefix();
        $table = $prefix . 'cuican_topic';
        $map = $prefix . 'cuican_topic_vod';
        $op = (string)($post['op'] ?? '');

        if ($op === 'create' || $op === 'save') {
            // 名称是唯一必填项：空名字的专题在客户端就是一张点不开的空白海报
            $name = trim((string)($post['name'] ?? ''));
            if ($name === '') {
                Guard::fail('topics', '专题名称不能为空');
            }
            $data = [
                'name' => mb_substr($name, 0, 100),
                'pic' => (string)($post['pic'] ?? ''),
                'blurb' => mb_substr((string)($post['blurb'] ?? ''), 0, 200),
                'sort' => (int)($post['sort'] ?? 0),
                'status' => (int)($post['status'] ?? 1) === 1 ? 1 : 0,
            ];
            $topicId = (int)($post['topic_id'] ?? 0);
            if ($topicId > 0) {
                Db::table($table)->where('topic_id', $topicId)->update($data);
            } else {
                $data['content'] = '';
                $data['created_at'] = time();
                Db::table($table)->insert($data);
            }
        } elseif ($op === 'bind') {
            $result = $this->bindFilms(
                $table,
                $map,
                (int)($post['topic_id'] ?? 0),
                (string)($post['vod_ids'] ?? ''),
            );
            // 成败都带回列表页显示，不静默失败：输错 id 时运营必须看到原因
            $query = ['a' => 'topics'];
            if ($result['topicMissing']) {
                $query['error'] = '专题不存在，请从下方「专题列表」点「编辑」进入后再绑定影片';
            } elseif ($result['missing'] !== []) {
                $query['error'] = '这些影片 id 在影片库里不存在，已跳过：'
                    . implode('、', $result['missing'])
                    . '；已成功绑定 ' . $result['bound'] . ' 部';
            } else {
                $query['notice'] = '已绑定 ' . $result['bound'] . ' 部'
                    . ($result['duplicate'] > 0 ? '，' . $result['duplicate'] . ' 部之前绑过已跳过' : '');
            }
            header('Location: ?' . http_build_query($query));
            exit;
        } elseif ($op === 'unbind') {
            Db::table($map)
                ->where('topic_id', (int)($post['topic_id'] ?? 0))
                ->where('vod_id', (int)($post['vod_id'] ?? 0))
                ->delete();
        } elseif ($op === 'move_topic') {
            Reorder::move($table, 'topic_id', (int)($post['topic_id'] ?? 0), (string)($post['dir'] ?? ''));
        } elseif ($op === 'move_vod') {
            Reorder::move(
                $map,
                'vod_id',
                (int)($post['vod_id'] ?? 0),
                (string)($post['dir'] ?? ''),
                ['topic_id' => (int)($post['topic_id'] ?? 0)]
            );
        } elseif ($op === 'delete') {
            $topicId = (int)($post['topic_id'] ?? 0);
            Db::table($table)->where('topic_id', $topicId)->delete();
            Db::table($map)->where('topic_id', $topicId)->delete();
        }
    }

    /**
     * 绑定影片。
     *
     * 三重校验，都不静默吞掉：
     * - 专题必须存在：输错专题 id 直接报错，不往一张孤儿映射表里写数据；
     * - 影片必须在 `mac_vod` 里存在：手输 id 很容易写错，绑定一个不存在的片子，
     *   客户端那边只会显示一张空海报，运营还以为是接口坏了；
     * - 重复绑定的跳过，重复提交不会叠加。
     *
     * @return array{bound:int,duplicate:int,missing:list<int>,topicMissing:bool}
     */
    private function bindFilms(string $topicTable, string $mapTable, int $topicId, string $vodIds): array
    {
        $result = ['bound' => 0, 'duplicate' => 0, 'missing' => [], 'topicMissing' => false];
        if ($topicId <= 0 || !Db::table($topicTable)->where('topic_id', $topicId)->find()) {
            $result['topicMissing'] = true;
            return $result;
        }
        $sort = (int)Db::table($mapTable)->where('topic_id', $topicId)->max('sort');
        foreach (explode(',', $vodIds) as $raw) {
            $vodId = (int)trim($raw);
            if ($vodId <= 0) {
                continue;
            }
            // 影片必须真实存在，否则绑上去也是一条死数据
            if (!Db::name('vod')->where('vod_id', $vodId)->find()) {
                $result['missing'][] = $vodId;
                continue;
            }
            if (Db::table($mapTable)->where('topic_id', $topicId)->where('vod_id', $vodId)->find()) {
                $result['duplicate']++;
                continue;
            }
            Db::table($mapTable)->insert(['topic_id' => $topicId, 'vod_id' => $vodId, 'sort' => ++$sort]);
            $result['bound']++;
        }
        return $result;
    }
}
