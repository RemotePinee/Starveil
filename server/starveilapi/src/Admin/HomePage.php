<?php
/**
 * 首页装修：轮播 / 编辑精选 / 专区 / 快捷入口 / 模块与底部菜单。
 *
 * 单独一个类是为了不让 Actions.php 继续膨胀；样式复用 View 的 sv-* 类，
 * 输出统一 View::e，写操作由 admin.php 统一校验 CSRF。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin;

use Starveil\Api\Admin\Page\BasePage;
use Starveil\Api\Admin\Ui\Select;
use Starveil\Api\Installer;
use Starveil\Api\Repo\Featured;
use Starveil\Api\Support\Guard;
use Starveil\Api\Support\Setting;
use Starveil\Api\Support\Media;
use think\Db;

final class HomePage extends BasePage
{
    private const PAGES = ['banner', 'featured', 'hot', 'guess', 'blocks', 'links', 'tabs'];

    /** 底栏「显示」上限：客户端的底栏最多画 5 个，多出来的会被静默截断。 */
    /** 底栏「显示」上限：客户端的底栏最多画 5 个，多出来的会被静默截断。 */
    private const MAX_VISIBLE_TABS = 5;
    private const PINNED_TABS = ['home', 'mine'];
    private const MODULES = ['banner', 'featured', 'links', 'blocks', 'hot', 'continue', 'topic', 'live', 'guess'];

    /** 模块的中文名：只用于后台展示，客户端仍按上面的英文键取值。 */
    private const MODULE_LABELS = [
        'banner' => '轮播图',
        'featured' => '编辑精选',
        'links' => '快捷入口',
        'blocks' => '首页专区',
        'hot' => '热度榜',
        'continue' => '继续观看',
        'topic' => '专题',
        'live' => '直播',
        'guess' => '猜你喜欢',
    ];

    /**
     * 快捷入口可用的图标：必须与客户端 `iconOf()` 的取值一一对应，
     * 不在表里的名字客户端会退成默认图标，所以这里只给选择、不给手填。
     */

    /** 底部菜单图标：同样受客户端 `iconOf()` 约束。 */
    private const TAB_ICONS = [
        'home' => '首页图标',
        'topic' => '专题图标',
        'rank' => '榜单图标',
        'vip' => '会员图标',
        'user' => '我的图标',
        // 客户端 bottomTabIcon() 里 shortplay / live 都有对应图标，之前只是后台没给选项
        'shortplay' => '短剧图标',
        'live' => '直播图标',
    ];

    /**
     * 每个按钮「点进去看什么」。
     *
     * 不再只限第二栏：五栏都能换，想把「榜单」那格改成直播就改成直播。
     * 客户端 `routeForMode()` 按 mode 决定渲染哪一页，所以这里就是唯一的真相来源。
     */
    private const TAB_MODES = [
        'home' => '首页',
        'topic' => '专题列表',
        'live' => '直播列表',
        'shortplay' => '短剧列表',
        'rank' => '榜单',
        'vip' => '会员中心',
        'user' => '个人中心',
    ];

    private const TAB_IDS = ['home', 'second', 'rank', 'member', 'mine'];


    public static function supports(string $page): bool
    {
        return in_array($page, self::PAGES, true);
    }

    /** @return array{title:string,html:string} */
    public function render(string $page): array
    {
        $result = match ($page) {
            'banner' => ['title' => '轮播', 'html' => $this->banner()],
            // 精选 / 热度榜 / 猜你喜欢共用 FeaturedPage，只是 position 与页面名不同
            'featured' => $this->featured(Featured::POSITION, '编辑精选', 'featured'),
            'hot' => $this->featured(Featured::POSITION_HOT, '热度榜', 'hot'),
            'guess' => $this->featured(Featured::POSITION_GUESS, '猜你喜欢', 'guess'),
            'blocks' => ['title' => '首页专区', 'html' => $this->blocks()],
            'links' => ['title' => '快捷入口', 'html' => $this->links()],
            default => ['title' => '模块与底部菜单', 'html' => $this->tabs()],
        };
        // 校验失败的原因统一显示在页面顶部：这个类里所有写操作都走 fail()
        $error = trim((string)($_GET['error'] ?? ''));
        if ($error !== '') {
            $result['html'] = '<p class="sv-hint sv-flash-err">'
                . View::e($error) . '</p>' . $result['html'];
        }
        return $result;
    }

    public function mutate(string $page, array $post): void
    {
        match ($page) {
            'banner' => $this->bannerAction($post),
            'featured' => (new FeaturedPage($this->csrf, $this->isCli))->mutate($post),
            'hot' => (new FeaturedPage($this->csrf, $this->isCli, Featured::POSITION_HOT))->mutate($post),
            'guess' => (new FeaturedPage($this->csrf, $this->isCli, Featured::POSITION_GUESS))->mutate($post),
            'blocks' => $this->blockAction($post),
            'links' => $this->linkAction($post),
            'tabs' => $this->tabsAction($post),
            default => null,
        };
    }

    // ---- 轮播 ----------------------------------------------------------

    private function banner(): string
    {
        $editId = (int)($_GET['edit'] ?? 0);
        $row = $editId > 0
            ? Db::table(Installer::prefix() . 'cuican_banner')->where('id', $editId)->find()
            : null;

        $form = $this->card(
            $row ? '编辑轮播 #' . $editId : '新增轮播',
            '<form method="post">' . $this->csrfField()
            . '<input type="hidden" name="op" value="save">'
            . ($row ? '<input type="hidden" name="id" value="' . $editId . '">' : '')
            . '<div class="sv-grid">'
            . $this->field('标题', 'title', (string)($row['title'] ?? ''), '留空自动用影片名')
            . $this->field('副标题', 'subtitle', (string)($row['subtitle'] ?? ''))
            . $this->field('图片地址', 'pic', (string)($row['pic'] ?? ''), '留空自动用影片海报；建议 700×900')
            . $this->field('影片 ID', 'vod_id', (string)($row['vod_id'] ?? '0'), '填了走详情（名称/海报自动取）；0 表示外链')
            . $this->field('外链', 'url', (string)($row['url'] ?? ''))
            . $this->field('排序', 'sort', (string)($row['sort'] ?? '0'), '越小越前')
            . $this->selectField('状态', 'status', ['1' => '启用', '0' => '停用'], (string)($row['status'] ?? '1'))
            . '</div><div class="sv-actions"><button class="sv-btn" type="submit">保存</button>'
            . ($row ? '<a class="sv-btn ghost" href="?a=banner">取消编辑</a>' : '')
            . '</div></form>'
        );

        $rows = Db::table(Installer::prefix() . 'cuican_banner')->order('sort asc,id asc')->select();
        $body = '';
        foreach ($rows as $item) {
            $body .= '<tr><td>' . (int)$item['id'] . '</td>'
                . '<td>' . View::e($item['title']) . '<br><small>' . View::e($item['subtitle']) . '</small></td>'
                . '<td>' . $this->targetOf($item) . '</td>'
                . '<td>' . (int)$item['sort'] . '</td>'
                . '<td>' . ((int)$item['status'] === 1
                    ? '<span class="sv-badge ok">启用</span>'
                    : '<span class="sv-badge off">停用</span>') . '</td>'
                . '<td class="sv-inline">'
                . '<a class="sv-btn ghost sm" href="?a=banner&edit=' . (int)$item['id'] . '">编辑</a>'
                . $this->postButton('delete', '删除', 'danger', ['id' => (int)$item['id']])
                . '</td></tr>';
        }

        return $form . $this->table(['id', '标题', '指向', '排序', '状态', ''], $body, 6);
    }

    private function bannerAction(array $post): void
    {
        $table = Installer::prefix() . 'cuican_banner';
        $op = (string)($post['op'] ?? '');
        if ($op === 'save') {
            $vodId = max((int)($post['vod_id'] ?? 0), 0);
            $title = trim((string)($post['title'] ?? ''));
            $pic = trim((string)($post['pic'] ?? ''));
            // 三者至少有一个：全空的轮播在客户端就是一张点不开的空白卡
            if ($title === '' && $pic === '' && $vodId <= 0) {
                Guard::fail('banner', '轮播至少要填一项：标题 / 图片地址 / 影片 ID');
            }
            $data = [
                'title' => mb_substr((string)($post['title'] ?? ''), 0, 100),
                'subtitle' => mb_substr((string)($post['subtitle'] ?? ''), 0, 200),
                'pic' => (string)($post['pic'] ?? ''),
                'vod_id' => max((int)($post['vod_id'] ?? 0), 0),
                'url' => (string)($post['url'] ?? ''),
                'sort' => (int)($post['sort'] ?? 0),
                'status' => (int)($post['status'] ?? 1) === 1 ? 1 : 0,
            ];
            $id = (int)($post['id'] ?? 0);
            if ($id > 0) {
                Db::table($table)->where('id', $id)->update($data);
            } else {
                Db::table($table)->insert($data);
            }
        } elseif ($op === 'delete') {
            Db::table($table)->where('id', (int)($post['id'] ?? 0))->delete();
        }
    }

    // ---- 专区 ----------------------------------------------------------

    private function blocks(): string
    {
        $create = $this->card(
            '新建专区',
            '<form method="post">' . $this->csrfField()
            . '<input type="hidden" name="op" value="create"><div class="sv-grid">'
            . $this->field('标题', 'title')
            . $this->field('副标题', 'subtitle')
            . $this->selectField('布局', 'layout', ['row' => '横滑', 'grid2' => '双列', 'grid3' => '三列'])
            . $this->field('排序', 'sort', '0')
            . '</div><div class="sv-actions"><button class="sv-btn" type="submit">新建</button></div></form>'
        );

        $bind = $this->card(
            '给专区选片',
            '<form method="post">' . $this->csrfField()
            . '<input type="hidden" name="op" value="bind"><div class="sv-grid">'
            . $this->field('专区 ID', 'block_id')
            . $this->field('影片 ID', 'vod_ids', '', '多个用英文逗号')
            . '</div><div class="sv-actions"><button class="sv-btn ghost" type="submit">加入专区</button></div></form>'
        );

        $rows = Db::table(Installer::prefix() . 'cuican_block')
            ->where('position', 'home')
            ->order('sort asc,id asc')
            ->select();
        $body = '';
        foreach ($rows as $row) {
            $count = Db::table(Installer::prefix() . 'cuican_block_vod')
                ->where('block_id', (int)$row['id'])
                ->count();
            $body .= '<tr><td>' . (int)$row['id'] . '</td>'
                . '<td>' . View::e($row['title']) . '<br><small>' . View::e($row['subtitle']) . '</small></td>'
                . '<td>' . View::e($row['layout']) . '</td><td>' . (int)$count . ' 部</td>'
                . '<td>' . (int)$row['sort'] . '</td>'
                . '<td class="sv-inline">'
                . $this->postButton('delete', '删除', 'danger', ['id' => (int)$row['id']])
                . '</td></tr>';
        }

        return $create . $bind . $this->table(['id', '标题', '布局', '影片数', '排序', ''], $body, 6);
    }

    private function blockAction(array $post): void
    {
        $blockTable = Installer::prefix() . 'cuican_block';
        $mapTable = Installer::prefix() . 'cuican_block_vod';
        $op = (string)($post['op'] ?? '');
        if ($op === 'create') {
            // 专区标题是客户端那一栏的标题，空标题等于一张没有名字的横滑区
            $title = trim((string)($post['title'] ?? ''));
            if ($title === '') {
                Guard::fail('blocks', '专区标题不能为空');
            }
            Db::table($blockTable)->insert([
                'title' => mb_substr($title, 0, 100),
                'subtitle' => mb_substr((string)($post['subtitle'] ?? ''), 0, 200),
                'layout' => in_array($post['layout'] ?? '', ['row', 'grid2', 'grid3'], true)
                    ? (string)$post['layout'] : 'row',
                'position' => 'home',
                'sort' => (int)($post['sort'] ?? 0),
                'status' => 1,
                'created_at' => time(),
            ]);
        } elseif ($op === 'bind') {
            $blockId = (int)($post['block_id'] ?? 0);
            // 三个必填：专区 id、影片 id，且专区与影片都必须真实存在
            if ($blockId <= 0) {
                Guard::fail('blocks', '专区 ID 不能为空');
            }
            if (!Db::table($blockTable)->where('id', $blockId)->find()) {
                Guard::fail('blocks', '专区不存在：' . $blockId);
            }
            $vodIds = [];
            foreach (explode(',', (string)($post['vod_ids'] ?? '')) as $raw) {
                $vodId = (int)trim($raw);
                if ($vodId > 0) {
                    $vodIds[$vodId] = $vodId;
                }
            }
            if ($vodIds === []) {
                Guard::fail('blocks', '影片 ID 不能为空');
            }
            $missing = [];
            foreach ($vodIds as $vodId) {
                if (!Db::name('vod')->where('vod_id', $vodId)->find()) {
                    $missing[] = $vodId;
                }
            }
            if ($missing !== []) {
                Guard::fail('blocks', '这些影片 id 不存在，已跳过：' . implode('、', $missing));
            }
            $sort = (int)Db::table($mapTable)->where('block_id', $blockId)->max('sort');
            foreach ($vodIds as $vodId) {
                // uk 兜底，这里先查一次只是为了让重复提交安静地跳过
                if (Db::table($mapTable)->where('block_id', $blockId)->where('vod_id', $vodId)->find()) {
                    continue;
                }
                Db::table($mapTable)->insert(['block_id' => $blockId, 'vod_id' => $vodId, 'sort' => ++$sort]);
            }
        } elseif ($op === 'delete') {
            $id = (int)($post['id'] ?? 0);
            Db::table($blockTable)->where('id', $id)->delete();
            Db::table($mapTable)->where('block_id', $id)->delete();
        }
    }

    // ---- 快捷入口 ------------------------------------------------------

    private function links(): string
    {
        $editId = (int)($_GET['edit'] ?? 0);
        $row = $editId > 0
            ? Db::table(Installer::prefix() . 'cuican_link')->where('id', $editId)->find()
            : null;
        $bg = (string)($row['bg'] ?? '');
        $preview = $bg !== '' ? Media::url($bg) : '';
        $error = $this->isCli ? null : session('sv_link_error');
        if ($error !== null && !$this->isCli) {
            session('sv_link_error', null);
        }

        $form = $this->card(
            $row ? '编辑快捷入口 #' . $editId : '新增快捷入口',
            ($error ? '<p class="sv-hint sv-flash-link">' . View::e((string)$error) . '</p>' : '')
            . '<form method="post" enctype="multipart/form-data">' . $this->csrfField()
            . '<input type="hidden" name="op" value="save">'
            . ($row ? '<input type="hidden" name="id" value="' . $editId . '">' : '')
            . '<div class="sv-grid">'
            . $this->field('标题', 'title', (string)($row['title'] ?? ''), '卡片主标题，最多 50 字')
            . $this->field('副标题', 'subtitle', (string)($row['subtitle'] ?? ''), '可空；显示在标题下一行')
            . $this->field('背景图地址', 'bg', $bg, '完整 URL（https://…）或 upload/... 路径。留空时影片/专题/直播会自动取对应封面；只要下面选了文件，保存时这一栏会被上传结果替换')
            . '<label class="sv-field"><span>背景图上传</span>'
            . '<input type="file" name="bg_file" accept="image/jpeg,image/png,image/gif,image/webp">'
            . '<small>必须 16:9。推荐 480×270（3x，最贴合卡片），要更清晰用 640×360；'
            . '其它比例会两端裁切。走 macCMS 上传配置（本地/远程/云存储）；上传成功后自动写入上面的「背景图地址」，不用再手填</small></label>'
            . $this->selectField('跳转类型', 'target_type', [
                'url' => '外链/页面路径',
                'vod' => '影片',
                'topic' => '专题',
                'live' => '直播',
                'second' => '第二栏',
            ], (string)($row['target_type'] ?? 'url'))
            . $this->field('目标 ID', 'target_id', (string)($row['target_id'] ?? '0'), '跳转类型选影片/专题/直播时填对应 id')
            . $this->field('地址', 'url', (string)($row['url'] ?? ''), '跳转类型选外链时填完整 URL 或站内路径')
            . $this->field('排序', 'sort', (string)($row['sort'] ?? '0'), '越小越前')
            . '</div>'
            . ($preview !== ''
                ? '<p class="sv-hint">当前背景图：<br><img src="' . View::e($preview)
                    . '" class="sv-thumb-md"></p>'
                : '')
            . '<div class="sv-actions"><button class="sv-btn" type="submit">保存</button>'
            . ($row ? '<a class="sv-btn ghost" href="?a=links">取消编辑</a>' : '')
            . '</div></form>'
        );

        $rows = Db::table(Installer::prefix() . 'cuican_link')->order('sort asc,id asc')->select();
        $body = '';
        foreach ($rows as $item) {
            $thumb = (string)($item['bg'] ?? '');
            $thumbUrl = $thumb !== '' ? Media::url($thumb) : '';
            $body .= '<tr><td>' . (int)$item['id'] . '</td>'
                . '<td>' . ($thumbUrl !== ''
                    ? '<img src="' . View::e($thumbUrl) . '" class="sv-thumb-sm">'
                    : '<small>无图</small>') . '</td>'
                . '<td>' . View::e($item['title']) . '<br><small>'
                . View::e((string)($item['subtitle'] ?? '')) . '</small></td>'
                . '<td>' . View::e($item['target_type']) . ' / ' . (int)$item['target_id'] . '</td>'
                . '<td>' . View::e($item['url']) . '</td>'
                . '<td class="sv-inline">'
                . '<a class="sv-btn ghost sm" href="?a=links&edit=' . (int)$item['id'] . '">编辑</a>'
                . $this->postButton('delete', '删除', 'danger', ['id' => (int)$item['id']])
                . '</td></tr>';
        }

        return $form . $this->table(['id', '背景', '标题/副标题', '跳转', '地址', ''], $body, 6);
    }

    private function linkAction(array $post): void
    {
        $table = Installer::prefix() . 'cuican_link';
        $op = (string)($post['op'] ?? '');
        if ($op === 'delete') {
            Db::table($table)->where('id', (int)($post['id'] ?? 0))->delete();
            return;
        }
        if ($op !== 'save') {
            return;
        }
        // 快捷入口是客户端首页的一张卡：没有标题就点不开，也没有任何信息可看
        $title = trim((string)($post['title'] ?? ''));
        if ($title === '') {
            Guard::fail('links', '快捷入口标题不能为空');
        }
        $bg = trim((string)($post['bg'] ?? ''));
        $uploaded = $this->uploadLinkBg();
        if ($uploaded !== null) {
            $bg = $uploaded;
        }

        $data = [
            'title' => mb_substr((string)($post['title'] ?? ''), 0, 50),
            'subtitle' => mb_substr((string)($post['subtitle'] ?? ''), 0, 50),
            'bg' => mb_substr($bg, 0, 255),
            'target_type' => in_array($post['target_type'] ?? '', ['url', 'vod', 'topic', 'live', 'second'], true)
                ? (string)$post['target_type'] : 'url',
            'target_id' => max((int)($post['target_id'] ?? 0), 0),
            'url' => mb_substr((string)($post['url'] ?? ''), 0, 255),
            'sort' => (int)($post['sort'] ?? 0),
        ];
        $id = (int)($post['id'] ?? 0);
        if ($id > 0) {
            Db::table($table)->where('id', $id)->update($data);
        } else {
            Db::table($table)->insert($data + ['status' => 1]);
        }
        // POST 后重定向：刷新页面不会把同一条再插一遍
        if (!$this->isCli) {
            header('Location: ?a=links');
            exit;
        }
    }

    /**
     * 背景图上传：走统一的 [Media::upload]。
     *
     * 落盘、格式校验、水印、远程存储仍由 macCMS 负责；这里统一的是调用方式
     * （源码要求传 `imgdata`）与落盘后的等比瘦身，细节见 `Support\Media`。
     */
    private function uploadLinkBg(): ?string
    {
        $file = Media::upload('bg_file', 'links');
        if ($file === null && !$this->isCli && !empty($_FILES['bg_file']['name'])) {
            session('sv_link_error', '图片上传失败');
        }
        return $file;
    }

    // ---- 模块 / 底部菜单 ------------------------------------------------

    private function tabs(): string
    {
        $modules = Setting::json('home_modules', self::MODULES);
        $tabs = Setting::json('bottom_tabs', self::defaultTabs());
        // 底栏校验失败（例如勾了 6 个）时给出明确提示，而不是静默丢弃本次保存
        $error = $this->isCli ? null : session('sv_tab_error');
        if ($error !== null && !$this->isCli) {
            session('sv_tab_error', null);
        }

        return $this->card(
            '首页模块与底部菜单',
            ($error ? '<p class="sv-hint sv-flash-link">' . View::e((string)$error) . '</p>' : '')
            . '<form method="post">' . $this->csrfField()
            . '<input type="hidden" name="op" value="save">'
            . $this->moduleRows($modules)
            . $this->iconSwitch()
            . $this->tabRows($tabs)
            . '<div class="sv-actions"><button class="sv-btn" type="submit">保存</button>'
            . '<span class="sv-hint sv-hint-inline">保存后客户端下一次刷新首页生效</span></div>'
            . '</form>'
        );
    }

    /**
     * 首页模块：勾选 = 显示，行顺序 = 展示顺序。
     *
     * 提交的仍是后端认识的逗号串，行编辑器只在浏览器里把它拼出来，
     * 没有 JS 时隐藏字段还是原值，保存逻辑一行没动。
     *
     * @param array<int,string> $modules
     */
    private function moduleRows(array $modules): string
    {
        $ordered = [];
        foreach ($modules as $id) {
            if (in_array($id, self::MODULES, true) && !in_array($id, $ordered, true)) {
                $ordered[] = $id;
            }
        }
        foreach (self::MODULES as $id) {
            if (!in_array($id, $ordered, true)) {
                $ordered[] = $id;
            }
        }

        $rows = '';
        foreach ($ordered as $id) {
            $on = in_array($id, $modules, true);
            $rows .= '<div class="sv-rrow sv-rrow-csv" data-row="' . View::e($id) . '">'
                . '<label class="sv-check"><input type="checkbox" class="js-on"' . ($on ? ' checked' : '') . '> '
                . View::e(self::MODULE_LABELS[$id] ?? $id) . '</label>'
                . '<span class="sv-code">' . View::e($id) . '</span>'
                . '<span class="sv-move">'
                . '<button class="sv-icon-btn" type="button" data-move="up" title="上移">↑</button>'
                . '<button class="sv-icon-btn" type="button" data-move="down" title="下移">↓</button>'
                . '</span></div>';
        }

        return '<div class="sv-divider">首页模块</div>'
            . '<p class="sv-desc">手机首页从上往下按这个顺序排。勾掉即隐藏，右边箭头调顺序。</p>'
            . '<div data-sv-rows="csv">'
            . '<input type="hidden" name="modules" data-sv-out value="' . View::e(implode(',', $modules)) . '">'
            . '<div class="sv-rows">' . $rows . '</div></div>';
    }

    /**
     * 底栏图标总开关。
     *
     * 关掉后客户端只画文字（类似优酷那种纯文字底栏），文字会自动放大一档；
     * 每个 tab 自己的 icon 值仍保留，随时可以再打开。
     */
    private function iconSwitch(): string
    {
        $on = Setting::int('bottom_tab_icons', 1) === 1;
        // 与下面「底部菜单」同级的区块标题，之前用 h4 看着像挂在模块列表里
        return '<div class="sv-divider">图标</div>'
            . '<label class="sv-check"><input type="checkbox" name="bottom_tab_icons" value="1"'
            . ($on ? ' checked' : '') . '> 底栏显示图标</label>'
            . '<p class="sv-desc">取消勾选后底栏只显示文字，字号会自动放大一档；'
            . '各按钮自己的图标配置不会被清除，随时可以勾回来。</p>';
    }

    /**
     * 底部菜单：原来是一个 JSON 文本框，运营看不懂也改不对，改成逐行表单。
     *
     * 输出格式仍是原来的 JSON（id/label/icon/visible/order/mode），
     * 由浏览器在提交前拼好；后端 tabsAction 完全不改。
     *
     * @param array<int,array<string,mixed>> $tabs
     */
    /** 各栏位的默认跳转目标。老数据没有 `mode` 时按它回填，避免一律落成专题页。 */
    private static function defaultModeFor(string $id): string
    {
        return match ($id) {
            'home' => 'home',
            'rank' => 'rank',
            'member' => 'vip',
            'mine' => 'user',
            default => 'topic',
        };
    }

    private function tabRows(array $tabs): string
    {
        $sorted = [];
        $seen = [];
        foreach ($tabs as $tab) {
            if (!is_array($tab)) {
                continue;
            }
            $id = (string)($tab['id'] ?? '');
            // 只校验形状（内部键名），不再限定 5 个固定槽位：行数由运营自己加减
            if (!self::isTabId($id) || isset($seen[$id])) {
                continue;
            }
            $seen[$id] = true;
            $sorted[] = $tab;
        }
        // 一条都没有（首装/清库）时给默认五栏；否则只保证首页与我的存在，
        // 其余槽位由运营自己增删，不能顺手把删掉的栏位补回来
        if ($sorted === []) {
            $sorted = self::defaultTabs();
        } else {
            foreach (self::requiredTabs() as $tab) {
                if (!isset($seen[$tab['id']])) {
                    $sorted[] = $tab;
                }
            }
        }

        $map = '';
        foreach ($sorted as $tab) {
            $map .= '<span' . ((string)($tab['id'] ?? '') === 'second' ? ' class="is-target"' : '') . '>'
                . View::e((string)($tab['label'] ?? $tab['id'])) . '</span>';
        }

        // 会员是会员体系的唯一入口：不管会员开没开，会员行的图标与"点进去看什么"
        // 都是锁死的（只允许改名字），所以 vip 从其它行的选项表里**始终**摘掉，
        // 否则会出现两个按钮都进会员中心。
        $memberOff = Setting::int('member_enabled', 1) === 0;
        $icons = self::TAB_ICONS;
        $mods = self::TAB_MODES;
        unset($icons['vip'], $mods['vip']);
        $visibleCount = 0;
        foreach ($sorted as $tab) {
            $isMemberRow = (string)($tab['id'] ?? '') === 'member';
            if ((bool)($tab['visible'] ?? true) && !($isMemberRow && $memberOff)) {
                $visibleCount++;
            }
        }
        $rows = '<div class="sv-rrow sv-rrow-tabs sv-rrow-head">'
            . '<span>按钮名称</span><span>图标</span><span>点进去看什么</span>'
            . '<span>显示</span><span>顺序</span></div>';
        foreach ($sorted as $tab) {
            $id = (string)$tab['id'];
            // 会员行：图标与"点进去看什么"恒定锁定（只允许改名字）；
            // 会员体系关闭时连名字、显示、排序一起锁。
            $pinned = $id === 'member';
            $locked = $pinned && $memberOff;
            // 会员体系开启时的会员行：恒显示、不可取消，但会占掉 5 个名额里的一个
            $fixed = $pinned && !$memberOff;
            // mode 缺省值按 id 给：老数据里 home 没存 mode，直接回落 topic 会把首页变成专题页
            $mode = (string)($tab['mode'] ?? '');
            if (!isset(self::TAB_MODES[$mode])) {
                $mode = self::defaultModeFor($id);
            }
            $disable = $locked ? ' disabled' : '';
            $rows .= '<div class="sv-rrow sv-rrow-tabs' . ($locked ? ' is-locked' : '')
                . ($pinned ? ' is-pinned' : '') . '" data-row="' . View::e($id) . '">'
                . '<input type="text" class="js-label" maxlength="12" value="'
                . View::e((string)($tab['label'] ?? $id)) . '"' . $disable . '>'
                // 会员行用完整选项表并固定成 vip：拿过滤后的表会回落成第一项（首页图标/首页）
                . $this->selectInline(
                    'js-icon',
                    $pinned ? self::TAB_ICONS : $icons,
                    $pinned ? 'vip' : (string)($tab['icon'] ?? $id),
                    $pinned || $locked,
                )
                . $this->selectInline(
                    'js-mode',
                    $pinned ? self::TAB_MODES : $mods,
                    $pinned ? 'vip' : $mode,
                    $pinned || $locked,
                )
                . '<label class="sv-check"><input type="checkbox" class="js-visible"'
                . ($locked
                    // data-locked 给前端上限脚本认：会员关闭时会员行不渲染，也不占名额
                    ? ' disabled data-locked="1"'
                    : ($fixed
                        ? ' checked disabled data-fixed="1"'
                        : ((bool)($tab['visible'] ?? true) ? ' checked' : ''))) . '>'
                // 补充说明另起一行（.sv-note），和勾选框挤一行会把「显示」列撑破
                . ' 显示'
                . ($locked
                    ? '<span class="sv-note">会员关闭：整行锁定</span>'
                    : ($pinned ? '<span class="sv-note">会员入口固定</span>' : ''))
                . '</label>'
                . '<span class="sv-move">'
                . '<button class="sv-icon-btn" type="button" data-move="up" title="上移"' . $disable . '>↑</button>'
                . '<button class="sv-icon-btn" type="button" data-move="down" title="下移"' . $disable . '>↓</button>'
                // 首页/我的不可删：删掉就没有导航入口，服务端保存时也会再补回来
                . ($pinned || $id === 'home' || $id === 'mine'
                    ? ''
                    : '<button class="sv-icon-btn sv-del" type="button" data-del-row title="删除这一栏">×</button>')
                . '</span></div>';
        }

        // 新增行模板：克隆后由前端补 id 并插入，控件结构与现有行一致
        $tpl = '<template data-row-template>'
            . '<div class="sv-rrow sv-rrow-tabs" data-row="">'
            . '<input type="text" class="js-label" maxlength="12" placeholder="按钮名称" value="">'
            . $this->selectInline('js-icon', $icons, 'topic')
            . $this->selectInline('js-mode', $mods, 'topic')
            . '<label class="sv-check"><input type="checkbox" class="js-visible"> 显示</label>'
            . '<span class="sv-move">'
            . '<button class="sv-icon-btn" type="button" data-move="up" title="上移">↑</button>'
            . '<button class="sv-icon-btn" type="button" data-move="down" title="下移">↓</button>'
            . '<button class="sv-icon-btn sv-del" type="button" data-del-row title="删除这一栏">×</button>'
            . '</span></div></template>';

        $over = $visibleCount > self::MAX_VISIBLE_TABS
            ? '<p class="sv-hint sv-flash-link">当前有 ' . $visibleCount . ' 个「显示」，客户端只会画前 '
                . self::MAX_VISIBLE_TABS . ' 个：保存时会按顺序取消靠后的那几个'
                . '（会员入口、首页与我的优先保留）。</p>'
            : '';
        return '<div class="sv-divider">底部菜单</div>'
            . '<div class="sv-tabmap">' . $map . '</div>'
            . '<p class="sv-desc">按钮数量不限，但「显示」最多勾 <b>' . self::MAX_VISIBLE_TABS
            . '</b> 个（当前 <b>' . $visibleCount . '</b> 个）。'
            . '「显示」＝放进底栏，取消＝从底栏移除；首页与我的始终保留、不可删除。'
            . '会员入口固定：图标与「点进去看什么」不可改，只允许改名字；'
            . '会员体系开启时会员恒显示并占掉一个名额（其余最多 4 个），超出名额时优先保留首页与我的，'
            . '关闭时整行锁定，其它行的图标与跳转里也不会出现会员。</p>'
            . $over
            . '<div data-sv-rows="tabs" data-max-visible="5">'
            . '<input type="hidden" name="tabs" data-sv-out value="'
            . View::e((string)json_encode($tabs, JSON_UNESCAPED_UNICODE)) . '">'
            . '<div class="sv-rows">' . $rows . '</div>'
            . '<div class="sv-actions"><button class="sv-btn sv-btn-ghost" type="button" data-add-row>'
            . '＋ 新增按钮</button></div>'
            . $tpl . '</div>';
    }

    /** 栏位 id 的形状约束：内部键名，参与 URL/JSON，只允许小写字母开头的短串。 */
    private static function isTabId(string $id): bool
    {
        return preg_match('/^[a-z][a-z0-9_]{0,15}$/', $id) === 1;
    }

    /** 必须存在的两栏：删掉就没有导航入口了。 @return array<int,array<string,mixed>> */
    /**
     * 把「显示」收敛到上限，返回被取消显示的栏位名。
     *
     * 会员体系开启时会员入口恒显示（客户端也靠它进会员中心），固定占一个位置，
     * 其余栏位最多 4 个；会员关闭时会员行不渲染，其余最多 5 个。
     *
     * 名额先给「首页 / 我的」，再按顺序给其它栏位：少一个运营位只是少个入口，
     * 少了首页或我的，用户就没有导航了。
     *
     * 会员占的那一个名额在遍历前就扣掉，和会员行排在列表第几位无关——否则会员排
     * 到最后时会先数满 5 个再叠上会员，还是 6 个。
     *
     * @param array<int,array<string,mixed>> $tabs
     * @return array{tabs:array<int,array<string,mixed>>,dropped:list<string>}
     */
    public static function clampVisibleTabs(array $tabs, bool $memberOn): array
    {
        $left = self::MAX_VISIBLE_TABS - ($memberOn ? 1 : 0);
        $reserved = [];
        $shown = 0;
        foreach ($tabs as $index => $tab) {
            $id = (string)($tab['id'] ?? '');
            if ($id === 'member') {
                $tabs[$index]['visible'] = $memberOn;
                continue;
            }
            if (in_array($id, self::PINNED_TABS, true) && (bool)($tab['visible'] ?? false)) {
                $reserved[$index] = true;
                $shown++;
            }
        }
        $dropped = [];
        foreach ($tabs as $index => $tab) {
            $id = (string)($tab['id'] ?? '');
            if ($id === 'member' || isset($reserved[$index]) || !(bool)($tab['visible'] ?? false)) {
                continue;
            }
            $shown++;
            if ($shown > $left) {
                $tabs[$index]['visible'] = false;
                $dropped[] = (string)($tab['label'] ?? $id);
            }
        }
        return ['tabs' => $tabs, 'dropped' => $dropped];
    }
    /** 首页与我的：底栏的兜底导航，客户端在全空时也只补这两栏。 */
    private static function requiredTabs(): array
    {
        return [
            ['id' => 'home', 'label' => '首页', 'icon' => 'home', 'visible' => true, 'order' => 1, 'mode' => 'home'],
            ['id' => 'mine', 'label' => '我的', 'icon' => 'user', 'visible' => true, 'order' => 99, 'mode' => 'user'],
        ];
    }


    /**
     * 会员体系从关到开时重新收敛底栏。
     *
     * 关闭期间会员行是锁定的、不占名额，运营可以把其它 5 栏都勾上；那一刻开会员，
     * 会员入口必须出现在底栏，5 个名额就超了。这里把会员点亮并挤掉排在最后的
     * 那一栏，返回被挤掉的栏位名给调用方提示——不这么做，客户端只会画前 5 个，
     * 运营勾的最后一栏凭空消失。
     *
     * @return list<string>
     */
    public static function syncMembershipTabs(): array
    {
        $tabs = Setting::json('bottom_tabs', []);
        if ($tabs === []) {
            return [];
        }
        usort($tabs, static fn (array $a, array $b): int => (int)($a['order'] ?? 0) <=> (int)($b['order'] ?? 0));
        $result = self::clampVisibleTabs($tabs, true);
        Setting::set('bottom_tabs', json_encode($result['tabs'], JSON_UNESCAPED_UNICODE));
        return $result['dropped'];
    }

    private function tabsAction(array $post): void
    {
        $raw = trim((string)($post['modules'] ?? ''));
        $modules = array_values(array_filter(
            array_map('trim', explode(',', $raw)),
            static fn (string $m): bool => $m !== '',
        ));
        $modules = array_values(array_intersect($modules, self::MODULES));
        Setting::set('home_modules', json_encode($modules !== [] ? $modules : self::MODULES, JSON_UNESCAPED_UNICODE));
        // 底栏图标总开关：关掉后客户端只画文字，字号自动放大一档
        Setting::set('bottom_tab_icons', empty($post['bottom_tab_icons']) ? '0' : '1');

        $tabs = json_decode((string)($post['tabs'] ?? ''), true);
        if (!is_array($tabs)) {
            return;
        }
        $clean = [];
        $seen = [];
        $memberOff = Setting::int('member_enabled', 1) === 0;
        // 会员行在会员体系关闭时是"整行锁定"：名称/图标/跳转/显示都还原成库里原值，
        // 否则运营改了文案、保存成功、界面上却看不到变化（下次打开又被锁回旧样式）。
        $previous = [];
        foreach (Setting::json('bottom_tabs', []) as $old) {
            if (is_array($old) && isset($old['id'])) {
                $previous[(string)$old['id']] = $old;
            }
        }
        $icons = self::TAB_ICONS;
        $mods = self::TAB_MODES;
        if ($memberOff) {
            // 会员关闭时其它行也不能再指向会员：图标与跳转白名单同步摘掉 vip
            unset($icons['vip'], $mods['vip']);
        }
        foreach ($tabs as $tab) {
            if (!is_array($tab)) {
                continue;
            }
            $id = (string)($tab['id'] ?? '');
            // id 只要形状合法即可：栏位数量由运营增删，显示上限另有校验
            if (!self::isTabId($id) || isset($seen[$id]) || count($clean) >= 20) {
                continue;
            }
            $seen[$id] = true;
            $old = is_array($previous[$id] ?? null) ? $previous[$id] : [];
            $locked = $id === 'member' && $memberOff;
            // 图标/跳转必须落在白名单里（会员关闭时 vip 已被移除，回落到栏位默认）
            $icon = (string)($locked ? ($old['icon'] ?? $id) : ($tab['icon'] ?? $id));
            if (!isset($icons[$icon])) {
                $icon = isset($icons[$id]) ? $id : 'topic';
            }
            $mode = (string)($locked ? ($old['mode'] ?? '') : ($tab['mode'] ?? ''));
            if (!isset($mods[$mode])) {
                $mode = self::defaultModeFor($id);
            }
            if (!isset($mods[$mode])) {
                $mode = 'home';
            }
            // 会员入口固定：图标与跳转恒为 vip，服务端强制覆写——
            // 前端那两个下拉是 disabled，但伪造请求仍可能提交别的值
            if ($id === 'member') {
                $icon = 'vip';
                $mode = 'vip';
            }
            $clean[] = [
                'id' => $id,
                'label' => mb_substr((string)($locked ? ($old['label'] ?? $id) : ($tab['label'] ?? $id)), 0, 12),
                'icon' => $icon,
                // 会员关闭时提交上来一定是 false（勾选框禁用），这里还原库里的值，
                // 否则重新开启会员后菜单不会自己回来
                'visible' => $locked ? (bool)($old['visible'] ?? true) : (bool)($tab['visible'] ?? true),
                'order' => (int)($tab['order'] ?? count($clean) + 1),
                'mode' => $mode,
            ];
        }
        foreach (self::requiredTabs() as $required) {
            if (!isset($seen[$required['id']])) {
                $clean[] = [
                    'id' => (string)$required['id'],
                    'label' => (string)$required['label'],
                    'icon' => (string)$required['icon'],
                    'visible' => true,
                    'order' => count($clean) + 1,
                    'mode' => (string)$required['mode'],
                ];
            }
        }
        usort($clean, static fn (array $a, array $b): int => $a['order'] <=> $b['order']);
        // 底栏最多 5 个；会员体系开启时会员入口固定占一个，其余最多 4 个
        $clamped = self::clampVisibleTabs($clean, !$memberOff);
        $clean = $clamped['tabs'];
        if ($clamped['dropped'] !== []) {
            $message = '已保存；底栏最多 ' . self::MAX_VISIBLE_TABS . ' 个按钮'
                . ($memberOff ? '' : '（会员入口固定占一个）')
                . '，已取消「' . implode('」「', $clamped['dropped']) . '」的显示';
            if (!$this->isCli) {
                session('sv_tab_error', $message);
            }
            // AJAX 保存要是只回笼统的"已保存"，用户看到的勾选却少了，
            // 会以为保存坏了；这里把被取消的栏位名直接带回去。
            if (Guard::isAjaxRequest()) {
                Guard::jsonOk($message);
            }
        }
    }

    /** @return array<int,array<string,mixed>> */
    private static function defaultTabs(): array
    {
        return [
            ['id' => 'home', 'label' => '首页', 'icon' => 'home', 'visible' => true, 'order' => 1],
            ['id' => 'second', 'label' => '专题', 'icon' => 'topic', 'visible' => true, 'order' => 2, 'mode' => 'topic'],
            ['id' => 'rank', 'label' => '榜单', 'icon' => 'rank', 'visible' => true, 'order' => 3],
            ['id' => 'member', 'label' => '会员', 'icon' => 'vip', 'visible' => true, 'order' => 4],
            ['id' => 'mine', 'label' => '我的', 'icon' => 'user', 'visible' => true, 'order' => 5],
        ];
    }

    // ---- 小工具 --------------------------------------------------------

    /**
     * 编辑精选 / 热度榜 / 猜你喜欢共用 FeaturedPage，只有 position 与标题不同。
     *
     * @return array{title:string,html:string}
     */
    private function featured(string $position, string $title, string $page): array
    {
        $typeId = max((int)($_GET['type_id'] ?? 0), 0);
        $rendered = (new FeaturedPage($this->csrf, $this->isCli, $position, $typeId, $page))->render();
        return ['title' => $title, 'html' => $rendered['html']];
    }

    private function targetOf(array $row): string
    {
        return (int)$row['vod_id'] > 0
            ? '影片 #' . (int)$row['vod_id']
            : ('外链 ' . View::e($row['url']));
    }

    /**
     * 行编辑器里的无标签下拉（列头已经写了字段名，避免每行重复）。
     *
     * @param array<string,string> $options
     */
    private function selectInline(string $class, array $options, string $selected, bool $disabled = false): string
    {
        return Select::render('', $options, $selected, $class, $disabled);
    }
}
