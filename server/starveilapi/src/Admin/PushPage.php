<?php
/**
 * 后台「消息推送」页。
 *
 * 发一条全站广播（写 cuican_message，user_id=0），客户端 `v1.main/messages` 拉列表、
 * `v1.main/push` 拿未读数；下方列出最近 20 条，方便确认发过什么、有没有发重。
 *
 * 与旧版的区别：以前这页被灰置——客户端只按条数亮红点，没有内容落地页，
 * 现在客户端补齐了消息中心，页面恢复可用，并支持公告/推送两种语义、模板、
 * 跳转、置顶与强制。
 *
 * 本页不显示「已读/未读」：status 是全表共享的一列，一个人读过全站就变已读，
 * 看了只会误导运营；按用户的已读存在 cuican_message_read（v2.6.5）。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin;

use Starveil\Api\Admin\Page\BasePage;
use Starveil\Api\Admin\Ui\Alert;
use Starveil\Api\Installer;
use Starveil\Api\Repo\Push;
use Starveil\Api\Support\Guard;
use Starveil\Api\Support\Media;
use think\Db;

final class PushPage extends BasePage
{
    /** 列表只展示最近这么多条，够运营核对当天内容。 */
    private const RECENT = 20;

    /**
     * 角标可选的图标：存 key，客户端用矢量图标渲染。
     *
     * 不用 Emoji：4 字节字符要求连接与列都是 utf8mb4，而 macCMS 的连接字符集是 utf8，
     * 改核心配置不在插件职责内；更关键的是 Emoji 在不同厂商 ROM 上的字形、基线、
     * 尺寸都不一致，做不了"和文字垂直居中、按主题着色"这类设计约束。
     * key 是纯 ASCII，客户端映射到本地图标资源，跨设备完全一致。
     *
     * @var array<string,string>
     */
    private const ICONS = [
        '' => '不显示图标',
        'fire' => '热播',
        'gift' => '福利',
        'crown' => '会员',
        'timer' => '限时',
        'tool' => '维护',
        'lantern' => '节日',
        'star' => '推荐',
        'film' => '影视',
        'notice' => '公告',
        'gem' => '专享',
    ];

    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '消息推送', 'html' => $this->flash() . $this->form() . $this->recent()];
    }

    /** 校验失败由 Guard::fail 重定向回来，错误串在 `?error=` 里；不显示出来就是"点了没反应"。 */
    private function flash(): string
    {
        $error = trim((string)($_GET['error'] ?? ''));
        return $error === '' ? '' : Alert::error($error);
    }

    public function mutate(array $post): void
    {
        $op = (string)($post['op'] ?? '');
        if ($op === 'delete') {
            (new Push())->remove((int)($post['msg_id'] ?? 0));
            return;
        }
        if ($op !== 'publish') {
            return;
        }

        $title = trim((string)($post['title'] ?? ''));
        if ($title === '') {
            // 空标题的广播在客户端是一行空白，等于发了一条看不见的消息，直接拒绝
            Guard::fail('push', '标题不能为空');
        }

        $template = (string)($post['template'] ?? 'text');
        $content = trim((string)($post['content'] ?? ''));
        // 纯文字模板的正文就是全部内容，空正文的弹窗只有标题，等于发了个空壳
        if ($template === 'text' && $content === '') {
            Guard::fail('push', '纯文字模板必须填正文');
        }

        $action = (string)($post['action_type'] ?? 'none');
        $value = trim((string)($post['action_value'] ?? ''));
        if ($action === 'url' && !preg_match('#^https?://#i', $value)) {
            Guard::fail('push', '跳转类型是「外链」时，参数必须是 http(s) 地址');
        }
        // 影片/分类的 id 为空时客户端会跳到 0：宁可在后台就拦下来，也不要发一条点不动的消息
        if (in_array($action, ['vod', 'type'], true) && !ctype_digit($value)) {
            Guard::fail('push', '跳转类型是「影片 / 分类」时，参数必须是数字 id');
        }

        (new Push())->publish([
            'kind' => (int)($post['kind'] ?? Push::KIND_PUSH),
            'template' => $template,
            'title' => $title,
            'content' => $content,
            // 卡片模板的整块内容：封面在这里定稿（上传 / 图库选择 / 手填地址 / 影片海报）
            'cover' => $this->resolveCover($post),
            // 药丸图标存 key（fire / gift / …），客户端映射成矢量图标
            'icon' => (string)($post['icon'] ?? ''),
            'badge' => (string)($post['badge'] ?? ''),
            'cta' => (string)($post['cta'] ?? ''),
            'action_type' => $action,
            // 不跳转时不存参数：留着上一次的旧值，下次切换跳转类型会变成"莫名的跳转"
            'action_value' => $action === 'none' ? '' : $value,
            'forced' => (string)($post['forced'] ?? '0') === '1',
            'pinned' => (string)($post['pinned'] ?? '0') === '1',
        ]);
    }

    /**
     * 卡片模板的背景图定稿：上传优先，其次从已上传里选，再次手填地址，
     * 最后按「跳转参数」里的影片 id 取海报。
     *
     * 影片 id 不再单独占一个字段：跳转类型选了「影片详情」时，参数本身就是 id，
     * 再让运营填一遍是同一个值抄两处，抄不一致就是"图是 A 片、点开是 B 片"。
     *
     * 四条来源最后都过一遍 [Media::url]：库里存的是 `upload/...` 相对路径，
     * 客户端要的是能直接加载的地址（与 `Repo\Links` 同一口径）。
     *
     * @param array<string,mixed> $post
     */
    private function resolveCover(array $post): string
    {
        // 选了文件就以文件为准：表单里残留的旧地址不该盖掉这次上传
        $uploaded = Media::upload('cover_file', Media::DIR);
        if ($uploaded !== null) {
            return Media::url($uploaded);
        }
        // 有文件名却拿不到地址，只可能是被 macCMS 的上传规则拒了（格式/体积/存储）
        if (!empty($_FILES['cover_file']['name'])) {
            Guard::fail('push', '背景图上传失败，请检查图片格式与大小');
        }

        // 从已上传图库里选的，与手填地址同一个字段，输入框里已经带着路径
        $cover = trim((string)($post['cover'] ?? ''));
        if ($cover !== '') {
            return Media::url($cover);
        }

        // 兜底：跳转影片时，直接拿这部片的海报
        if ((string)($post['action_type'] ?? '') !== 'vod') {
            return '';
        }
        $vodId = (int)($post['action_value'] ?? 0);
        if ($vodId <= 0) {
            return '';
        }
        $pic = (string)Db::name('vod')->where('vod_id', $vodId)->value('vod_pic');
        if (trim($pic) === '') {
            Guard::fail('push', '跳转影片没有海报，请手动选一张背景图');
        }
        return Media::url($pic);
    }

    // ---- 表单 ----------------------------------------------------------

    private function form(): string
    {
        $today = (new Push())->todayCount();

        return $this->card(
            '发一条公告 / 推送',
            '<p class="sv-hint">客户端在「消息中心」按 <b>置顶 → 最新</b> 排序展示；'
            . '今天已发 ' . $today . ' 条。已读按用户单独记录，这里看不到"谁读了"。</p>'
            . '<form method="post" enctype="multipart/form-data">' . $this->csrfField()
            . '<input type="hidden" name="op" value="publish"><div class="sv-grid">'
            . $this->selectField('类型', 'kind', [
                (string)Push::KIND_PUSH => '推送（运营活动）',
                (string)Push::KIND_ANNOUNCE => '公告（系统通知）',
            ], (string)Push::KIND_PUSH, '只决定消息中心列表里那一行的标签，弹窗样式一律由「模板」决定')
            . $this->selectField('模板', 'template', [
                'text' => '纯文字（无插画）',
                'notice' => '公告（粉色喇叭插画）',
                'update' => '更新（暖橙场记板插画）',
                'card' => '卡片（上图下文 + 左上角标）',
            ], 'text', '模板决定弹窗样式：notice / update 只换顶部插画，card 是上图下文的大卡片。')
            . $this->field('标题', 'title', '', '客户端弹窗标题，最多 128 字')
            . $this->field('主按钮文案', 'cta', '', '留空客户端默认「立即查看」')
            // 卡片专属配置：包一层 [data-card-only]，由 admin.js 按「模板」选择显隐。
            // 用 wrapper 而不是给控件加类：sv-grid 的子项就是一格，wrapper 正好占一格，
            // 隐藏时整格收起，后面的字段自动回流，不会留下空洞。
            . '<div class="sv-card-only" data-card-only>'
            . $this->selectField('左上角标图标', 'icon', self::ICONS, '', 'card 模板药丸左边的图标，不选则不画')
            . '</div>'
            . '<div class="sv-card-only" data-card-only>'
            . $this->field('左上角标文字', 'badge', '', '药丸文字，最多 8 字；留空不显示药丸')
            . '</div>'
            // 地址、上传、图库三件合成一条整宽行：分到两格会被网格按顺序甩开，看着像三个不相干的设置
            . '<div class="sv-card-only sv-full" data-card-only>'
            . '<div class="sv-field"><span>背景图</span>'
            . '<div class="sv-img-row">'
            . '<input name="cover" value="" placeholder="图片地址：完整 URL 或 upload/... 路径，可留空">'
            . '<input type="file" name="cover_file" accept="image/jpeg,image/png,image/gif,image/webp">'
            . '</div>'
            . $this->coverPicker()
            . '<small>建议 16:9。选文件以上传结果为准，其次用填写的地址；'
            . '都留空则按「跳转参数」里的影片 id 自动取海报</small></div></div>'
            . $this->textarea('正文', 'content', '', '纯文字模板必填；notice / update 模板可只放一两行补充说明，card 可留空')
            . $this->selectField('跳转类型', 'action_type', [
                'none' => '不跳转',
                'vod' => '影片详情',
                'type' => '分类列表',
                'url' => '外链',
            ], 'none', '选「影片详情」时，下面的参数就是影片 id，背景图留空会自动用它的海报')
            . $this->field('跳转参数', 'action_value', '', '影片/分类填 id；外链填 http(s) 地址')
            . $this->selectField('强制弹出', 'forced', ['0' => '否', '1' => '是（必须看完才能关）'], '0',
                '开启后弹窗点外部、按返回都关不掉，右上角也不给关闭按钮，只能点主按钮')
            . $this->selectField('置顶', 'pinned', ['0' => '否', '1' => '是（排在列表最前）'], '0')
            . '</div><div class="sv-actions"><button class="sv-btn" type="submit">立即发布</button>'
            . '</div></form>'
        );
    }

    // ---- 最近发过的 ----------------------------------------------------

    private function recent(): string
    {
        $rows = Db::table(Installer::prefix() . 'cuican_message')
            ->where('user_id', Push::BROADCAST_USER_ID)
            ->order('msg_id desc')
            ->limit(self::RECENT)
            ->select();

        $body = '';
        foreach ($rows as $row) {
            $body .= '<tr><td>' . (int)$row['msg_id'] . '</td>'
                . '<td>' . self::kindTag((int)($row['kind'] ?? Push::KIND_PUSH)) . '</td>'
                . '<td>' . self::e((string)($row['title'] ?? '')) . '</td>'
                . '<td>' . self::e((string)($row['template'] ?? 'text')) . '</td>'
                . '<td><small>' . self::e(self::actionText($row)) . '</small></td>'
                . '<td>' . self::e(self::flagText($row)) . '</td>'
                . '<td>' . self::e(date('Y-m-d H:i', (int)($row['created_at'] ?? 0))) . '</td>'
                // 删除用隐藏 input 传 op：admin.js 是 new FormData(form) 提交，
                // 靠按钮的 name/value 分发动作会丢，必须走隐藏字段
                . '<td><form method="post">' . $this->csrfField()
                . '<input type="hidden" name="op" value="delete">'
                . '<input type="hidden" name="msg_id" value="' . (int)$row['msg_id'] . '">'
                . '<button class="sv-btn sv-btn-ghost" type="submit">删除</button>'
                . '</form></td></tr>';
        }

        return $this->table(['id', '类型', '标题', '模板', '跳转', '标记', '时间', '操作'], $body, 8);
    }

    // ---- 列表里的小标签 --------------------------------------------------

    private static function kindTag(int $kind): string
    {
        return $kind === Push::KIND_ANNOUNCE
            ? '<span class="sv-badge warn">公告</span>'
            : '<span class="sv-badge ok">推送</span>';
    }

    /** @param array<string,mixed> $row */
    private static function actionText(array $row): string
    {
        $value = (string)($row['action_value'] ?? '');
        return match ((string)($row['action_type'] ?? 'none')) {
            'vod' => '影片 #' . $value,
            'type' => '分类 #' . $value,
            'url' => mb_substr($value, 0, 40),
            default => '—',
        };
    }

    /** @param array<string,mixed> $row */
    private static function flagText(array $row): string
    {
        $flags = [];
        if ((int)($row['pinned'] ?? 0) === 1) {
            $flags[] = '置顶';
        }
        if (!empty($row['forced'])) {
            $flags[] = '强制';
        }
        return $flags === [] ? '—' : implode(' / ', $flags);
    }

    /**
     * 「从已上传里选一张」：扫 [Media::DIR] 下最近的图，做成缩略图行。
     *
     * 选中哪张就把它写进上面的 `cover` 输入框——不另起一个字段，
     * 服务端只认一个来源，避免"选了图库里的、地址框里还留着旧地址"这种二义。
     * 这一行是纯前端联动，由 `admin.js` 的 data-cover-pick 绑定；没有 JS 时
     * 图库不出现，运营仍可手填地址或上传，功能不残缺。
     */
    private function coverPicker(): string
    {
        $images = Media::recentImages(self::RECENT);
        if ($images === []) {
            return '<small class="sv-hint">图库还是空的：上传一张，或先在其它页面传图，这里就能一键选。</small>';
        }
        $cells = '';
        foreach ($images as $path) {
            $cells .= '<button type="button" class="sv-pick" data-cover-pick="'
                . View::e($path) . '" title="' . View::e($path) . '">'
                . '<img src="' . View::e(Media::url($path)) . '" alt="" loading="lazy"></button>';
        }
        return '<div class="sv-pick-row"><span>或选已上传的</span><div class="sv-pick-list">' . $cells . '</div></div>';
    }
}
