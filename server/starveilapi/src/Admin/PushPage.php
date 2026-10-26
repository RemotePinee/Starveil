<?php
/**
 * 后台「每日推送」页。
 *
 * 发一条全站广播（写 cuican_message，user_id=0），客户端下一次拉 `v1.vod/push`
 * 就会看到；下方列出最近 20 条，方便确认今天发了什么、有没有发重。
 *
 * 不新建表：广播消息与私信共用一张表，读接口已经在用（见 Repo/Push 的注释）。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin;

use Starveil\Api\Admin\Page\BasePage;
use Starveil\Api\Installer;
use Starveil\Api\Repo\Push;
use think\Db;

final class PushPage extends BasePage
{
    /** 列表只展示最近这么多条，够运营核对当天内容。 */
    private const RECENT = 20;

    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '每日推送', 'html' => $this->form() . $this->recent()];
    }

    public function mutate(array $post): void
    {
        if ((string)($post['op'] ?? '') !== 'publish') {
            return;
        }

        $title = trim((string)($post['title'] ?? ''));
        $content = trim((string)($post['content'] ?? ''));
        // 空标题的广播在客户端是一行空白，等于发了一条看不见的消息，直接拒绝
        if ($title === '' || $content === '') {
            return;
        }

        (new Push())->publish($title, $content);
    }

    // ---- 表单 ----------------------------------------------------------

    private function form(): string
    {
        $today = (new Push())->count();

        return $this->card(
            '发一条全站推送（灰度中，暂不可用）',
            // 灰置原因写在最显眼的位置：不锁住的话，运营会以为发了就有人看到
            '<p class="sv-hint sv-flash-disabled"><b>⚠ 客户端还没做推送落地页。</b>'
            . 'App 现在只会调 `v1.main/push` 拿一个<b>条数</b>，在首页搜索框右上角亮个红点；'
            . '点它进的是搜索页，看不到这里发出的标题和正文。'
            . '为了避免"发了却没人看到"的错觉，本页暂时禁用，等客户端补上消息列表页再开放。</p>'
            . '<fieldset disabled class="sv-disabled-group">'
            . '<p class="sv-hint">今天已发 ' . $today . ' 条（仅供参考，当前不可提交）。</p>'
            . '<form method="post">' . $this->csrfField()
            . '<input type="hidden" name="op" value="publish"><div class="sv-grid">'
            . $this->field('标题', 'title', '', '客户端弹窗标题，最多 128 字')
            . $this->textarea('正文', 'content', '', '可留空行，但必须有内容')
            . '</div><div class="sv-actions"><button class="sv-btn" type="submit" disabled>立即推送</button>'
            . '</div></form></fieldset>'
        );
    }

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
                . '<td>' . View::e($row['title']) . '</td>'
                . '<td><small>' . View::e(strip_tags((string)($row['content'] ?? ''))) . '</small></td>'
                . '<td>' . View::e(date('Y-m-d H:i', (int)$row['created_at'])) . '</td>'
                . '<td>' . ((int)$row['status'] === 0
                    ? '<span class="sv-badge warn">未读</span>'
                    : '<span class="sv-badge off">已读</span>') . '</td></tr>';
        }

        return $this->table(['id', '标题', '正文', '时间', '状态'], $body, 5);
    }
}
