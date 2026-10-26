<?php
/**
 * 直播页：频道增删改、批量改分组/删除、M3U 导入、分页列表。
 *
 * 分组相关的 UI 与登记在 [LiveGroupPanel]：面板、下拉、提交时的名字解析都放那边，
 * 只负责频道本身。
 *
 * 分页注意 count 与 select 必须各建一个 Query——同一个 Query 先 count 再取列表
 * 会污染 bind，报 1146/Invalid parameter number（macCMS TP5 的坑）。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;
use Starveil\Api\Admin\Ui\Badge;
use Starveil\Api\Admin\Ui\Pager;
use Starveil\Api\Admin\Ui\Select;
use Starveil\Api\Installer;
use Starveil\Api\Support\Guard;
use Starveil\Api\Support\M3u;
use Starveil\Api\Support\Reorder;
use Starveil\Api\Support\Text;
use think\Db;

final class LivePage extends BasePage
{
    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '直播', 'html' => $this->live()];
    }

    public function mutate(array $post): void
    {
        $this->liveAction($post);
    }

    private function live(): string
    {
        $prefix = Installer::prefix();
        $groupsUi = new LiveGroupPanel($this->csrf, $this->isCli);
        $editId = (int)($_GET['edit'] ?? 0);
        $editing = $editId > 0
            ? Db::table($prefix . 'cuican_zhibo')->where('zhibo_id', $editId)->find()
            : null;

        // 现有分组（分组表 + 频道里用到的名字）：添加/编辑直播、批量改分组、
        // 批量导入三处的下拉候选用同一份，避免「刚建的分组在自己页面里选不到」
        $existingGroups = LiveGroupPanel::names($prefix);

        $form = '<div class="sv-card"><div class="sv-section-title">'
            . ($editing ? '编辑直播 #' . $editId : '添加直播') . '</div><form method="post">'
            . $this->csrfField()
            . '<input type="hidden" name="op" value="' . ($editing ? 'save' : 'create') . '">'
            . ($editing ? '<input type="hidden" name="zhibo_id" value="' . $editId . '">' : '')
            . '<div class="sv-grid">'
            . $this->field('名称', 'name', (string)($editing['name'] ?? ''))
            . $groupsUi->select(
                'gname',
                (string)($editing['gname'] ?? ''),
                $existingGroups,
                'sv-live-group-form',
            )
            . $this->field('封面地址', 'img', (string)($editing['img'] ?? ''))
            . $this->field('播放地址', 'url', (string)($editing['url'] ?? ''), 'm3u8 / flv')
            . $this->field('排序', 'sort', (string)($editing['sort'] ?? '0'), '越小越前')
            . $this->selectField(
                '状态',
                'status',
                ['1' => '启用', '0' => '停用'],
                (string)($editing['status'] ?? '1')
            )
            . '</div><div class="sv-actions"><button class="sv-btn" type="submit">'
            . ($editing ? '保存' : '添加') . '</button>'
            . ($editing ? '<a class="sv-btn ghost" href="?a=live">取消编辑</a>' : '')
            . '</div></form>'
            . '<p class="sv-hint">每行可「编辑」，用「上移」「下移」调整顺序；排序值越小越前。</p></div>';

        // 批量导入：网上公开直播源是 m3u/m3u8 或 txt 播放列表，
        // 一条条手工录入不现实，这里填 URL 直接解析入库（重复地址自动跳过）
        $imported = (int)($_GET['imported'] ?? 0);
        $skipped = (int)($_GET['skipped'] ?? 0);
        $errorMsg = trim((string)($_GET['error'] ?? ''));
        $errorNotice = $errorMsg !== ''
            ? '<p class="sv-hint sv-flash-err">' . self::e($errorMsg) . '</p>'
            : '';
        $importNotice = ($imported + $skipped) > 0
            ? '<p class="sv-hint">上次导入：新增 ' . $imported . ' 条，跳过 ' . $skipped
                . ' 条（重复或非 https）。只导入 https 源：Android 会拦掉 http 明文流。</p>'
            : '';
        $form .= '<div class="sv-card"><div class="sv-section-title">批量导入播放列表</div>'
            . '<form method="post">'
            . $this->csrfField()
            . '<input type="hidden" name="op" value="import">'
            . $this->field(
                'M3U / TXT 地址',
                'm3u_url',
                '',
                '填播放列表的 http/https 地址，支持 .m3u / .m3u8 / .txt。'
                . 'M3U 取 #EXTINF 里的频道名与台标；TXT 认「分组,#genre#」开头、'
                . '下面每行「频道名,地址」的写法。'
                . '单份最多 2000 条，地址重复的自动跳过；非 https 的源会被剔除'
            )
            . $groupsUi->picker($existingGroups)
            . '<div class="sv-actions"><button class="sv-btn" type="submit">开始导入</button></div>'
            . '</form>' . $errorNotice . $importNotice . '</div>';

        // 分组管理：可以在这里先建空分组（导入时直接选），也可以删掉不再用的分组
        $form .= $groupsUi->panel($prefix);

        // 分页：导入一次就上百条，全渲染会让页面长得离谱、批量按钮被顶到几屏之外。
        // 注意 count 与 select 必须各建一个 Query——同一个 Query 先 count 再取列表
        // 会污染 bind，报 1146/Invalid parameter number（macCMS TP5 的坑）。
        $pageSize = 20;
        $total = (int)Db::table($prefix . 'cuican_zhibo')->count();
        $pageCount = max(1, (int)ceil($total / $pageSize));
        $page = min(max((int)($_GET['page'] ?? 1), 1), $pageCount);
        // 分组筛选条已撤：上面的「分组」卡片已经把每个分组和频道数列出来了，
        // 再来一排"分组 全部 …"的按钮是同一条信息的第二遍，纯占地方。
        // 列表默认展示全部频道，分组只作为每行的标签和批量设置的目标。
        $rows = Db::table($prefix . 'cuican_zhibo')
            ->order('sort asc,zhibo_id asc')
            ->page($page, $pageSize)
            ->select();

        $body = '';
        foreach ($rows as $row) {
            $id = (int)$row['zhibo_id'];
            // checkbox 用 form 属性挂到表格外的批量表单上：
            // 行内已经有独立的小表单（编辑/删除），再嵌套一个 form 是不合法的
            $body .= '<tr><td><input type="checkbox" name="ids[]" form="liveBatch"'
                . ' value="' . $id . '" class="js-row"></td>'
                . '<td>' . $id . '</td><td>' . self::e($row['name']) . '</td>'
                . '<td>' . self::e(trim((string)($row['gname'] ?? '')) !== ''
                    ? (string)$row['gname']
                    : '未分组') . '</td>'
                . '<td><span class="sv-code">' . self::e($row['url']) . '</span></td>'
                . '<td>' . (int)$row['sort'] . '</td>'
                . '<td>' . Badge::onOff((int)$row['status'] === 1) . '</td>'
                . '<td><div class="sv-inline">'
                . '<a class="sv-btn ghost sm" href="?a=live&edit=' . $id . '">编辑</a>'
                . $this->postButton('move', '上移', 'ghost', ['zhibo_id' => $id, 'dir' => 'up'])
                . $this->postButton('move', '下移', 'ghost', ['zhibo_id' => $id, 'dir' => 'down'])
                . $this->postButton('delete', '删除', 'danger', ['zhibo_id' => $id])
                . '</div></td></tr>';
        }
        // 分页放表格下方，右侧对齐——表格类页面的常规位置
        $pager = Pager::render('live', $page, $pageCount, $total);
        // 批量操作只占表格上方一行：勾完直接点，不用翻到底
        // 批量操作：一个表单两个提交按钮，靠按钮的 name="op" 区分动作。
        // ids 由 JS 在提交前从勾选行收集（见 View 的 js）：表格里的复选框用的是
        // `form` 属性外挂，浏览器兼容性不稳，实测会出现"提示成功但一条没改"。
        // 批量设置分组：用后台统一的自定义下拉，选「＋ 新建分组…」时旁边出现输入框
        $groupMap = ['' => '未分组'];
        foreach ($existingGroups as $group) {
            $groupMap[(string)$group] = (string)$group;
        }
        $groupMap[LiveGroupPanel::GROUP_NEW] = '＋ 新建分组…';
        $batch = '<form method="post" id="liveBatch" class="sv-inline">' . $this->csrfField()
            . '<input type="hidden" name="page" value="' . $page . '">'
            . '<input type="hidden" name="ids" id="sv-live-ids" value="">'
            . '<span class="sv-select-cell">'
            . Select::render('gname', $groupMap, '', 'js-group-new') . '</span>'
            // 用 sv-input：表单外的裸输入框需要显式带上后台的输入框样式，
            // 否则它就是一个没圆角没聚焦色的原生框
            // display 由 admin.js 切换（选中「＋ 新建分组…」时显示），只能留内联
            . '<input type="text" class="sv-input" name="gname_new" id="sv-live-group-new"'
            . ' maxlength="64" placeholder="新分组名" style="display:none;width:160px">'
            . '<button class="sv-btn sm" type="submit" name="op" value="batchGroup">设置分组</button>'
            . '<button class="sv-btn danger sm" type="submit" name="op" value="batchDelete">删除选中</button>'
            . '<span class="sv-desc">勾选左侧方框后点这里；只作用于当前页选中的条目</span></form>';

        // 页面顶部的操作结果：分组增删、批量改分组、批量删除都靠它回话，
        // 不弹 alert，操作完页面自己刷新一次就能看到结果
        $banner = '';
        $topError = trim((string)($_GET['error'] ?? ''));
        $topNotice = trim((string)($_GET['notice'] ?? ''));
        $grouped = (int)($_GET['grouped'] ?? 0);
        $removed = (int)($_GET['removed'] ?? 0);
        if ($topError !== '') {
            $banner = '<p class="sv-hint sv-flash-err">' . self::e($topError) . '</p>';
        } elseif ($topNotice !== '') {
            $banner = '<p class="sv-hint sv-flash-ok">' . self::e($topNotice) . '</p>';
        } elseif ($grouped > 0) {
            $banner = '<p class="sv-hint sv-flash-ok">已把 ' . $grouped . ' 个频道改到对应分组</p>';
        } elseif ($removed > 0) {
            $banner = '<p class="sv-hint sv-flash-ok">已删除 ' . $removed . ' 个频道</p>';
        } elseif (($grouped + $removed) === 0 && isset($_GET['grouped'])) {
            $banner = '<p class="sv-hint sv-flash-err">没有选中任何频道：先勾选左侧方框</p>';
        }
        return $banner . $form . '<div class="sv-card"><div class="sv-section-title">直播列表</div>'
            . $batch
            // 地址列是完整 URL，长度不可控：固定布局 + 单行省略，
            // 否则整张表被撑出横向滚动条，后面的排序/状态/操作全被推出视野
            . '<table class="sv-table sv-table-fixed sv-table-live">'
            . '<tr><th><input type="checkbox" class="js-all" form="liveBatch"></th>'
            . '<th>id</th><th>名称</th><th>分组</th>'
            . '<th>地址</th>'
            . '<th>排序</th><th>状态</th>'
            . '<th></th></tr>'
            . ($body !== '' ? $body : '<tr><td colspan="8" class="sv-empty">还没有直播</td></tr>')
            . '</table>'
            . $pager
            . '</div>';
    }

    private function liveAction(array $post): void
    {
        $table = Installer::prefix() . 'cuican_zhibo';
        $op = (string)($post['op'] ?? '');
        // 分组表：分组独立维护，频道只在 gname 上存名字
        $groupTable = Installer::prefix() . 'cuican_zhibo_group';
        if ($op === 'create' || $op === 'save') {
            $id = (int)($post['zhibo_id'] ?? 0);
            $rawUrl = trim((string)($post['url'] ?? ''));
            // 名称与播放地址都是必需的：没有地址的频道点开就是一片黑
            if (trim((string)($post['name'] ?? '')) === '') {
                Guard::fail('live', '频道名称不能为空');
            }
            if ($rawUrl === '') {
                Guard::fail('live', '播放地址不能为空');
            }
            // 明文 http 会被 Android 的 cleartext 策略拦掉，直接拦在保存这一步，
            // 而不是等用户在客户端点了才发现播不了
            if (stripos($rawUrl, 'https://') !== 0) {
                Guard::fail('live', '播放地址必须是 https：http 明文流会被系统拦截，无法播放');
            }
            $data = [
                'name' => Text::forDb(mb_substr(trim((string)($post['name'] ?? '')), 0, 100)),
                // 选了「＋ 新建分组…」时实际名字在 `gname_new` 里
                'gname' => Text::forDb(LiveGroupPanel::resolve($post)),
                'img' => Text::forDb((string)($post['img'] ?? '')),
                'url' => Text::forDb((string)($post['url'] ?? '')),
                'status' => (int)($post['status'] ?? 1) === 1 ? 1 : 0,
                'sort' => (int)($post['sort'] ?? 0),
            ];
            // 输入的新分组名自动登记，避免"客户端看得到、分组管理里没有"
            LiveGroupPanel::remember($groupTable, (string)$data['gname']);
            if ($id > 0) {
                Db::table($table)->where('zhibo_id', $id)->update($data);
            } else {
                Db::table($table)->insert($data);
            }
        } elseif ($op === 'move') {
            Reorder::move($table, 'zhibo_id', (int)($post['zhibo_id'] ?? 0), (string)($post['dir'] ?? ''));
        } elseif ($op === 'delete') {
            Db::table($table)->where('zhibo_id', (int)($post['zhibo_id'] ?? 0))->delete();
        } elseif ($op === 'batchGroup') {
            $ids = $this->postIds($post);
            if ($ids === []) {
                Guard::fail('live', '没有选中任何频道：先勾选左侧方框');
            }
            // 选了「＋ 新建分组…」时实际名字在 `gname_new` 里
            $gname = LiveGroupPanel::resolve($post);
            LiveGroupPanel::remember($groupTable, $gname);
            Db::table($table)->whereIn('zhibo_id', $ids)->update(['gname' => $gname]);
            $label = $gname !== '' ? $gname : '未分组';
            if (Guard::isAjaxRequest()) {
                Guard::jsonOk('已把 ' . count($ids) . ' 个频道改到「' . $label . '」');
            }
            $pageTo = max(1, (int)($post['page'] ?? 1));
            header('Location: ?a=live&page=' . $pageTo . '&grouped=' . count($ids));
            exit;
        } elseif ($op === 'batchDelete') {
            // 同 batchGroup：ids 由 JS 收集，可能是逗号串也可能是数组
            $ids = $this->postIds($post);
            if ($ids === []) {
                Guard::fail('live', '没有选中任何频道：先勾选左侧方框');
            }
            Db::table($table)->whereIn('zhibo_id', $ids)->delete();
            if (Guard::isAjaxRequest()) {
                Guard::jsonOk('已删除 ' . count($ids) . ' 个频道');
            }
            // 删完回原页：清空一整页时页码会越界，列表页自己会夹到最后一页
            $pageTo = max(1, (int)($post['page'] ?? 1));
            header('Location: ?a=live&page=' . $pageTo . '&removed=' . count($ids));
            exit;
        } elseif ($op === 'import') {
            $url = trim((string)($post['m3u_url'] ?? ''));
            if ($url === '') {
                Guard::fail('live', '播放列表地址不能为空');
            }
            $this->importM3u($url, LiveGroupPanel::resolve($post));
        } elseif ($op === 'groupAdd') {
            $name = Text::forDb(mb_substr(trim((string)($post['group_name'] ?? '')), 0, 64));
            if ($name === '') {
                Guard::fail('live', '分组名不能为空');
            }
            if (LiveGroupPanel::nameExists($groupTable, $name)) {
                Guard::fail('live', '已经有同名分组了：' . $name);
            }
            Db::table($groupTable)->insert(['name' => $name, 'sort' => 0, 'created_at' => time()]);
            if (Guard::isAjaxRequest()) {
                Guard::jsonOk('分组已添加：' . $name);
            }
            header('Location: ?a=live&notice=' . urlencode('分组已添加：' . $name));
            exit;
        } elseif ($op === 'groupDelete') {
            $name = trim((string)($post['group_name'] ?? ''));
            if ($name === '') {
                Guard::fail('live', '分组名不能为空');
            }
            // 删分组＝整组下线：组里的频道一起删。运营要的就是这个，
            // 但也意味着不可恢复，所以回执里把条数说清楚，别让人以为只删了空壳。
            $ids = $this->idsByName($table, $name);
            if ($ids !== []) {
                Db::table($table)->whereIn('zhibo_id', $ids)->delete();
            }
            $row = LiveGroupPanel::findByName($groupTable, $name);
            if ($row !== null) {
                Db::table($groupTable)->where('group_id', (int)$row['group_id'])->delete();
            }
            $removed = count($ids);
            $summary = $removed > 0
                ? '已删除分组「' . $name . '」及其 ' . $removed . ' 个频道'
                : '已删除分组「' . $name . '」（该组下没有频道）';
            if (Guard::isAjaxRequest()) {
                Guard::jsonOk($summary);
            }
            header('Location: ?a=live&notice=' . urlencode($summary));
            exit;
        }
    }

    /**
     * 某个分组下的频道 id（删分组时连频道一起删）。
     *
     * 同样不走 `WHERE gname = ?`：连接字符集与表不一致时 MySQL/MariaDB 会报 1267，
     * 把 gname 读回来在 PHP 里比，几百条的代价可以忽略。
     *
     * @return list<int>
     */
    private function idsByName(string $table, string $name): array
    {
        $ids = [];
        foreach (Db::table($table)->field('zhibo_id,gname')->select() as $row) {
            if (trim((string)($row['gname'] ?? '')) === $name) {
                $ids[] = (int)$row['zhibo_id'];
            }
        }
        return $ids;
    }

    /**
     * 把提交上来的选中 id 归一成整数数组。
     *
     * 两种来源都要认：
     * - 老路径 `ids[]`（复选框直接参与表单提交）；
     * - 新路径 `ids`（一行逗号串，由 JS 在提交前收集，见 View 的 js）。
     * 后者是为了绕开"复选框用 form 属性外挂、部分环境不参与提交"的问题。
     *
     * @return list<int>
     */
    private function postIds(array $post): array
    {
        $raw = $post['ids'] ?? [];
        if (is_string($raw)) {
            $raw = explode(',', $raw);
        }
        return array_values(array_filter(
            array_map('intval', (array)$raw),
            static fn (int $id): bool => $id > 0,
        ));
    }

    /**
     * 导入播放列表（M3U / M3U8 / TXT，格式由内容判断）。
     *
     * 只按播放地址去重（同一份列表反复导入不会翻倍），单份最多 2000 条（解析器上限）。
     * 一条都没拿到、也没跳过任何东西，说明拉取失败或格式不认识，直接报出来——
     * 否则页面上"什么都没发生"，用户只能猜。
     */
    private function importM3u(string $url, string $gname = ''): void
    {
        $table = Installer::prefix() . 'cuican_zhibo';
        // 手工指定的分组优先于源里的 group-title
        $forcedGroup = Text::forDb(mb_substr(trim($gname), 0, 64));
        $items = M3u::parse(M3u::fetch($url));
        $seen = [];
        foreach (Db::table($table)->field('url')->select() as $row) {
            $seen[(string)$row['url']] = true;
        }
        $sort = (int)Db::table($table)->max('sort');
        $added = 0;
        // 地址重复的条数：解析器已经剔除过非 https，跳过的基本只剩重复，
        // 这条计数还能顺带判断"这份源是不是广告文件"（见下面的提示）
        $duplicated = 0;
        foreach ($items as $item) {
            $stream = trim($item['url']);
            if ($stream === '' || isset($seen[$stream])) {
                $duplicated++;
                continue;
            }
            $gname = $forcedGroup !== ''
                ? $forcedGroup
                : Text::forDb(mb_substr(trim((string)($item['group'] ?? '')), 0, 64));
            // 导入用到的分组名逐个登记，分组管理里才能看到、才能预置顺序
            LiveGroupPanel::remember(Installer::prefix() . 'cuican_zhibo_group', $gname);
            Db::table($table)->insert([
                // 台名/台标来自外部文件，可能带 emoji 或非法字节，写库前先净化
                'name' => Text::forDb(mb_substr($item['name'] !== '' ? $item['name'] : '直播频道', 0, 100)),
                'img' => Text::forDb(mb_substr((string)$item['logo'], 0, 255)),
                'gname' => $gname,
                // 地址也要按字符切：substr 按字节切会把多字节字符劈成非法序列，照样 1366
                'url' => Text::forDb(mb_substr($stream, 0, 500)),
                'sort' => ++$sort,
            ]);
            $seen[$stream] = true;
            $added++;
        }
        if ($added === 0 && $duplicated === 0) {
            Guard::fail('live', '没解析到任何频道：地址打不开，或不是 m3u / txt 播放列表');
        }
        $summary = '已导入 ' . $added . ' 条，跳过 ' . $duplicated . ' 条（地址重复）';
        // 正常频道列表里每个台都是各自的地址；重复占了一半以上，基本是作者的
        // 公告/广告 txt——那类文件只有十来行，名字是"温馨提醒""请打赏观看"，
        // 地址反复指向同一个 ad.mp4。不拦着导，但要说清楚，别让人以为是解析错了。
        if ($added >= 2 && $duplicated >= $added) {
            $summary .= '。注意：重复地址占了一半以上，这份源看着像公告/广告文件，不是频道列表';
        }
        if (Guard::isAjaxRequest()) {
            // 后台其余写操作都回 JSON；这里如果走 302，前端 fetch 拿到的是整页 HTML，
            // r.json() 直接失败，用户只会看到"网络错误"，还以为没导进去
            Guard::jsonOk($summary);
        }
        header('Location: ?a=live&imported=' . $added . '&skipped=' . $skipped);
        exit;
    }
}
