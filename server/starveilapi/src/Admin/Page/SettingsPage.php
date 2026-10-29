<?php
/**
 * 运营设置页（渲染）：一页一组标签，每组一个独立表单。
 *
 * 字段表在 [SettingsSchema]，写操作在 [SettingsSave]；本类只负责把控件画出来，
 * 因此不会因为保存逻辑改动而受影响。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Support\Media;
use Starveil\Api\Support\Setting;

final class SettingsPage extends BasePage
{
    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '运营设置', 'html' => $this->settings()];
    }

    public function mutate(array $post): void
    {
        // 设置页有两种提交：解析配置(save_player) 与 分组设置(save)。
        // 分组设置要先接住图片上传，再写文本值（见 settingsAction）。
        $save = new SettingsSave($this->csrf, $this->isCli);
        if ((string)($post['op'] ?? '') === 'save_player') {
            $save->savePlayer($post);
            return;
        }
        $save->settingsAction($post);
    }

    private function settings(): string
    {
        // 一组一页：标签切换只切显示，每组仍是独立表单、独立保存
        $panes = [['sv-set-play', '播放解析', $this->playerCard()]];
        $index = 1;
        foreach (SettingsSchema::GROUPS as $group => $fields) {
            $panes[] = ['sv-set-' . $index, (string)$group, $this->groupCard((string)$group, $fields)];
            $index++;
        }

        $tabs = '';
        $body = '';
        foreach ($panes as $i => [$id, $label, $card]) {
            $on = $i === 0;
            $tabs .= '<button type="button" data-pane-tab="' . $id . '"'
                . ($on ? ' class="is-active"' : '') . '>' . self::e($label) . '</button>';
            $body .= '<div class="sv-pane"' . ($on ? '' : ' hidden') . ' data-pane="' . $id . '">'
                . $card . '</div>';
        }

        return '<div class="sv-card"><div class="sv-ptabs">' . $tabs . '</div>'
            . '<p class="sv-hint">一组一页，切标签换页；每组单独保存，互不影响。</p></div>'
            . $body;
    }

    /**
     * 一个设置分组：卡片 + 独立表单。
     *
     * @param array<string,array{0:string,1:string}> $fields
     */
    private function groupCard(string $group, array $fields): string
    {
        $html = '<div class="sv-card">'
            . '<div class="sv-section-title">' . self::e($group) . '</div>'
            . (isset(SettingsSchema::GROUP_DESC[$group])
                ? $this->groupNote((string)SettingsSchema::GROUP_DESC[$group])
                : '');
        // enctype 是图片上传必需的：普通表单拿不到文件，$_FILES 会是空数组
        $html .= '<form method="post" enctype="multipart/form-data"><div class="sv-grid sv-grid-set">'
            . $this->csrfField();
        // 「广告平台」组里除平台选择外都依赖 TradPlus：选「不接聚合」时整组灰置，
        // 避免运营以为填了 adUnitId 就会生效。灰置与禁用都由前端脚本按当前下拉值实时切。
        $platformGroup = $group === '广告平台';
        $dividers = SettingsSchema::GROUP_DIVIDERS[$group] ?? [];
        foreach ($fields as $key => [$label, $hint]) {
            // 分组内的分割线：广告这类"多个位挤在一页"的组，每个位前面插一条标题线，
            // 运营不用再靠字段名自己判断哪几项属于同一个广告位
            if (isset($dividers[$key])) {
                $html .= '<div class="sv-divider sv-divider-full">'
                    . self::e($dividers[$key]) . '</div>';
            }
            $cell = in_array($key, SettingsSchema::SENSITIVE, true)
                ? $this->secretField($label, 's[' . $key . ']', (string)Setting::get($key), $hint)
                : $this->settingField($key, $label, $hint);
            // URL 与长文案占满一行：两列栅格里 220px 的输入框只能露出地址前半截
            if (!$platformGroup && $this->isWideField($key)) {
                $cell = str_replace('class="sv-field"', 'class="sv-field sv-full"', $cell);
            }
            if ($platformGroup && $key !== 'ad_platform') {
                // 打标记，交给脚本统一 disable + 调透明度；服务端不再判断一次
                $cell = str_replace('<label class="sv-field', '<label class="sv-field sv-dep', $cell);
            }
            $html .= $cell;
        }
        return $html . '</div><div class="sv-actions"><button class="sv-btn" type="submit">保存'
            . self::e($group) . '</button></div></form></div>';
    }

    /**
     * 该字段是否独占一行。
     *
     * 判据是"内容本身长"而不是按分组硬编码：外链、隐私文案、图片/视频在窄栅格里
     * 只能看到一小段，必须占满整行才可读。
     */
    private function isWideField(string $key): bool
    {
        return isset(SettingsSchema::IMAGE[$key])
            || isset(SettingsSchema::VIDEO[$key])
            || str_ends_with($key, '_url')
            || $key === 'ad_consent_text';
    }

    /**
     * 分组说明：短说明直接显示，长说明折进 `<details>`。
     *
     * 广告组那段是"为什么只有两个位接 SDK"的设计理由，几百字铺在顶部会把字段推到
     * 屏幕外；折叠后标题还在，需要的人点开看。
     */
    private function groupNote(string $desc): string
    {
        if (mb_strlen($desc) <= 90) {
            return '<p class="sv-desc">' . self::e($desc) . '</p>';
        }
        return '<details class="sv-more"><summary>展开说明</summary>'
            . '<p class="sv-desc">' . self::e($desc) . '</p></details>';
    }

    /**
     * 解析接口：普通输入框，不再让运营手写 JSON。
     *
     * 只有「解析接口地址」是必填；其余三项留空即可，服务端会合成完整配置。
     */
    private function playerCard(): string
    {
        $info = Setting::playerInfo();
        return '<div class="sv-card" id="sv-set-play"><div class="sv-section-title">播放解析</div>'
            . '<p class="sv-desc">App 播放非直链片源时，用这里的解析接口拼地址。只有第一项必填。</p>'
            . '<form method="post">' . $this->csrfField()
            . '<input type="hidden" name="op" value="save_player">'
            . '<div class="sv-grid">'
            . $this->field('解析接口地址', 'parse2', (string)$info['parse2'], '例如 https://jx.example.com/json?url=')
            . $this->field(
                '直链正则',
                'features',
                (string)$info['features'],
                '可空；命中的地址直接播放，不走解析。逗号分隔多条，'
                . '直接写 m3u8,mp4 就行，不必加反斜杠 —— '
                . '写成 \\\\.m3u8 会被当成「字面反斜杠」，反而一条都命中不了'
            )
            . $this->field('需要请求头的地址正则', 'issethead', (string)$info['issethead'], '可空')
            . $this->field('请求头', 'headers', (string)$info['headers'], '可空；格式 Referer:https://a||User-Agent:xxx')
            . '</div><div class="sv-actions"><button class="sv-btn" type="submit">保存解析配置</button></div>'
            . '<p class="sv-hint">客户端播放时会拼成「解析接口地址 + 影片地址」。'
            . '不填的话 App 只能播放直链（m3u8/mp4），其它片源会提示解析失败。</p>'
            . '</form></div>';
    }

    /** 设置项渲染：下拉型走 select，其余仍用文本框。 */
    private function settingField(string $key, string $label, string $hint): string
    {
        $value = (string)Setting::get($key);
        // 图片型优先判断：它既要文本框也要文件框，不能落进下面的单选/多选分支
        if (isset(SettingsSchema::IMAGE[$key])) {
            return $this->imageField($label, 's[' . $key . ']', SettingsSchema::IMAGE[$key], $value, $hint);
        }
        // 视频型只给文件框：外链在它上面一行（ad_reward_video），两种值分开存
        if (isset(SettingsSchema::VIDEO[$key])) {
            return $this->videoField($label, SettingsSchema::VIDEO[$key], $value, $hint);
        }
        if (isset(SettingsSchema::MULTI[$key])) {
            return $this->checkGroupField($label, 's[' . $key . ']', SettingsSchema::MULTI[$key], $value, $hint);
        }
        return isset(SettingsSchema::SELECT[$key])
            ? $this->selectField($label, 's[' . $key . ']', SettingsSchema::SELECT[$key], $value, $hint)
            : $this->field($label, 's[' . $key . ']', $value, $hint);
    }

    /**
     * 图片型设置：外链文本框 + 文件上传框 + 当前值预览。
     *
     * 两个输入共用一个 setting 键：文本框是 `s[key]`，文件是固定字段名（见 SettingsSchema::IMAGE）。
     * 同时提交时**文件优先**（见 settingsAction）。预览用 Media::url 归一化，
     * 所以云存储、CDN、站内相对路径都能直接显示。
     */
    private function imageField(string $label, string $name, string $uploadName, string $value, string $hint): string
    {
        $url = Media::url($value);
        // 缩略图跟着输入行走，不再单独占一块高度：5 个位 × 各自的预览堆下来太长
        $thumb = $url === '' ? '' : '<img class="sv-thumb" src="' . self::e($url) . '" alt="当前图片">';
        return '<label class="sv-field sv-full"><span>' . self::e($label) . '</span>'
            . '<div class="sv-img-row">'
            . '<input name="' . self::e($name) . '" value="' . self::e($value) . '"'
            . ' placeholder="https 外链，或选右边的文件">'
            . '<input type="file" name="' . self::e($uploadName) . '"'
            . ' accept="image/jpeg,image/png,image/webp">'
            . $thumb
            . '</div>'
            // 通用说明统一写在分组抬头里，这里只留该位独有的尺寸与裁切要求
            . ($hint !== '' ? '<small>' . self::e($hint) . '</small>' : '')
            . '</label>';
    }

    /**
     * 视频型设置：只有文件框 + 当前文件链接。
     *
     * 不做 `<video>` 预览：后台一打开设置页就要拉一次 20MB 片源，纯属拖慢；
     * 运营想知道传的是哪个文件，点链接在新标签里播即可。
     * 当前值用 Media::url 归一化，云存储、CDN、站内相对路径都能点开。
     */
    private function videoField(string $label, string $uploadName, string $value, string $hint): string
    {
        $url = Media::url($value);
        // 与图片行同构：左边一个只读框显示当前文件（点得开），右边选文件。
        // 只读框只做展示，不参与提交——视频值由上传键写入，避免两种来源互相覆盖。
        $shown = $value === '' ? '' : $value;
        $link = $url === ''
            ? '<small>当前未上传；播放时回退到上面的外链地址</small>'
            : '<small>当前文件：<a href="' . self::e($url) . '" target="_blank" rel="noreferrer">'
                . self::e($value) . '</a> · 重新上传会覆盖它</small>';
        return '<label class="sv-field sv-full"><span>' . self::e($label) . '</span>'
            . '<div class="sv-img-row">'
            . '<input value="' . self::e($shown) . '" placeholder="尚未上传视频" readonly>'
            . '<input type="file" name="' . self::e($uploadName) . '"'
            . ' accept="video/mp4,video/webm">'
            . '</div>' . $link
            . ($hint !== '' ? '<small>' . self::e($hint) . '</small>' : '')
            . '</label>';
    }

    /**
     * 多选组：勾上的键以数组形式提交（`s[key][]`），保存时合成 JSON。
     *
     * 不用原生 select multiple：后台每点一次都会反选，运营经常误关掉另一种登录方式。
     *
     * @param array<string,string> $options
     */
    private function checkGroupField(
        string $label,
        string $name,
        array $options,
        string $value,
        string $hint = '',
    ): string {
        $decoded = json_decode($value === '' ? '[]' : $value, true);
        $chosen = is_array($decoded) ? array_map('strval', $decoded) : [];
        // 不能再套 `.sv-field`：后台 CSS 里 `.sv-field input{width:100%}`，
        // 复选框会被拉成整行宽、文字顶到最右边（运营看到的就是"框在中间、字在最右"）。
        // 后台还有 `.sv-grid label{...}` 这类后代规则会改写 display / justify-content，
        // 所以这里的关键属性全部带 `!important`，不依赖样式表里的默认值。
        $html = '<div style="grid-column:1/-1!important;width:100%!important;margin:2px 0 6px">'
            . '<div style="font-size:12px;color:#6b7280;margin-bottom:8px">' . self::e($label) . '</div>'
            . '<div style="display:flex!important;flex-wrap:wrap;align-items:center;'
            . 'justify-content:flex-start!important;gap:10px 28px;width:100%!important">';
        foreach ($options as $key => $text) {
            $html .= '<label style="display:flex!important;align-items:center!important;'
                . 'justify-content:flex-start!important;gap:8px!important;white-space:nowrap;'
                . 'width:auto!important;min-width:0!important;margin:0!important;padding:0!important;'
                . 'border:0!important;background:none!important;float:none!important;font-weight:400">'
                . '<input type="checkbox" name="' . self::e($name) . '[]" value="' . self::e((string)$key) . '"'
                . ' style="width:16px!important;height:16px!important;min-width:16px!important;'
                . 'margin:0!important;flex:0 0 16px!important;position:static!important"'
                . (in_array((string)$key, $chosen, true) ? ' checked' : '') . '>'
                . '<span style="font-size:13px;color:#1f2329;margin:0!important;padding:0!important;'
                . 'float:none!important">' . self::e($text) . '</span></label>';
        }
        $html .= '</div>'
            . ($hint !== '' ? '<div style="font-size:11px;color:#a0a6b0;line-height:1.5;margin-top:6px">'
                . self::e($hint) . '</div>' : '')
            . '</div>';
        return $html;
    }

    private function secretField(string $label, string $name, string $value, string $hint = ''): string
    {
        $status = $value !== '' ? '已设置，留空表示不修改' : '未设置';
        return '<label class="sv-field"><span>' . self::e($label) . '</span>'
            . '<input type="password" name="' . self::e($name) . '" value=""'
            . ' autocomplete="new-password" placeholder="' . self::e($status) . '">'
            . ($hint !== '' ? '<small>' . self::e($hint) . '</small>' : '') . '</label>';
    }
}
