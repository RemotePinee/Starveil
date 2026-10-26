<?php
/**
 * 支付页：macCMS 易支付配置状态 + 插件自己的通道增删改 + 回调地址。
 *
 * 两套配置是「能复用就复用」的关系：检测到 macCMS 的在线支付就提示可以一键复制成
 * 插件通道，没检测到就让运营在下面手填。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Installer;
use Starveil\Api\Support\Guard;
use Starveil\Api\Support\MacPay;
use think\Db;

final class PayPage extends BasePage
{
    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '支付通道', 'html' => $this->pay()];
    }

    public function mutate(array $post): void
    {
        $this->payAction($post);
    }

    private function pay(): string
    {
        $macBox = $this->macPayBox();
        $editing = (string)($_GET['edit'] ?? '');
        $channel = $editing !== ''
            ? Db::table(Installer::prefix() . 'cuican_pay_channel')->where('code', $editing)->find()
            : null;
        $config = $channel ? (json_decode((string)$channel['config'], true) ?: []) : [];
        $notify = $this->notifyUrl();

        $form = '<div class="sv-card"><div class="sv-section-title">'
            . ($channel ? '编辑通道：' . self::e($channel['name']) : '新增支付通道') . '</div>'
            . '<form method="post">' . $this->csrfField() . '<input type="hidden" name="op" value="save">'
            . '<div class="sv-grid">'
            . $this->field('标识', 'code', (string)($channel['code'] ?? ''), '小写英文，例如 epay')
            . $this->field('名称', 'name', (string)($channel['name'] ?? ''))
            . $this->selectField('驱动', 'driver', ['epay' => '易支付 Epay'], (string)($config['driver'] ?? 'epay'))
            . $this->field('网关地址', 'url', (string)($config['url'] ?? ''), '以 /submit.php 结尾')
            . $this->field('商户 PID', 'pid', (string)($config['pid'] ?? ''))
            . $this->field('商户 KEY', 'key', (string)($config['key'] ?? ''), '只存服务端，不下发给客户端')
            . $this->selectField('支付方式', 'type', ['alipay' => '支付宝', 'wxpay' => '微信'], (string)($config['type'] ?? 'alipay'))
            . $this->selectField('状态', 'status', ['1' => '启用', '0' => '停用'], (string)($channel['status'] ?? '1'))
            . $this->field('排序', 'sort', (string)($channel['sort'] ?? '0'))
            . '</div><div class="sv-actions"><button class="sv-btn" type="submit">保存通道</button>'
            . ($channel ? '<a class="sv-btn ghost" href="?a=pay">取消编辑</a>' : '') . '</div></form></div>';

        $notifyBox = '<div class="sv-card"><div class="sv-section-title">支付回调地址</div>'
            . '<div class="sv-inline"><span class="sv-code">' . self::e($notify) . '</span>'
            . '<button class="sv-btn ghost sm" data-copy="' . self::e($notify) . '">复制</button></div>'
            . '<p class="sv-hint">填到易支付后台的「异步通知地址」。客户端下单会带上 order_code，'
            . '回调验签用通道里的 KEY；金额与订单不一致会拒绝。</p></div>';

        $rows = Db::table(Installer::prefix() . 'cuican_pay_channel')->order('sort asc,code asc')->select();
        $body = '';
        foreach ($rows as $row) {
            $cfg = json_decode((string)$row['config'], true) ?: [];
            $on = (int)$row['status'] === 1;
            $body .= '<tr><td><span class="sv-code">' . self::e($row['code']) . '</span></td>'
                . '<td>' . self::e($row['name']) . '</td><td>' . self::e($cfg['driver'] ?? '-') . '</td>'
                . '<td>' . self::e($cfg['type'] ?? '-') . '</td>'
                . '<td>' . ($on ? '<span class="sv-badge ok">启用</span>' : '<span class="sv-badge off">停用</span>') . '</td>'
                . '<td class="sv-inline">'
                . '<a class="sv-btn ghost sm" href="?a=pay&edit=' . urlencode((string)$row['code']) . '">编辑</a>'
                . '<form method="post">' . $this->csrfField() . '<input type="hidden" name="op" value="toggle">'
                . '<input type="hidden" name="code" value="' . self::e($row['code']) . '">'
                . '<button class="sv-btn ghost sm" type="submit">' . ($on ? '停用' : '启用') . '</button></form>'
                . '<form method="post">' . $this->csrfField() . '<input type="hidden" name="op" value="delete">'
                . '<input type="hidden" name="code" value="' . self::e($row['code']) . '">'
                . '<button class="sv-btn danger sm" type="submit">删除</button></form>'
                . '</td></tr>';
        }
        $table = '<div class="sv-card"><div class="sv-section-title">已有通道</div>'
            . '<table class="sv-table"><tr><th>标识</th><th>名称</th><th>驱动</th><th>方式</th><th>状态</th><th></th></tr>'
            . ($body !== '' ? $body : '<tr><td colspan="6" class="sv-empty">还没有通道，先添加一个</td></tr>')
            . '</table></div>';

        return $macBox . $notifyBox . $form . $table;
    }

    private function payAction(array $post): void
    {
        $table = Installer::prefix() . 'cuican_pay_channel';
        $op = (string)($post['op'] ?? '');
        $code = strtolower(preg_replace('/[^a-z0-9_]/', '', (string)($post['code'] ?? '')));
        if ($op === 'import_mac') {
            $mac = MacPay::epayChannel();
            if ($mac !== null) {
                $data = ['name' => '易支付', 'status' => 1, 'sort' => $mac['sort'], 'config' => $mac['config']];
                if (Db::table($table)->where('code', 'epay')->find()) {
                    Db::table($table)->where('code', 'epay')->update($data);
                } else {
                    Db::table($table)->insert(['code' => 'epay'] + $data);
                }
            }
            return;
        }
        if ($op === 'save') {
            // 标识、名称、网关地址都必需：缺一个这条通道在客户端就是点不动的
            if ($code === '') {
                Guard::fail('pay', '通道标识不能为空（只允许字母、数字、下划线）');
            }
            $name = trim((string)($post['name'] ?? ''));
            if ($name === '') {
                Guard::fail('pay', '通道名称不能为空');
            }
            $url = trim((string)($post['url'] ?? ''));
            if ($url === '') {
                Guard::fail('pay', '支付网关地址不能为空');
            }
            $config = json_encode([
                'driver' => in_array($post['driver'] ?? '', ['epay'], true) ? (string)$post['driver'] : 'epay',
                'url' => (string)($post['url'] ?? ''),
                'pid' => (string)($post['pid'] ?? ''),
                'key' => (string)($post['key'] ?? ''),
                'type' => in_array($post['type'] ?? '', ['alipay', 'wxpay'], true) ? (string)$post['type'] : 'alipay',
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            $data = [
                'name' => mb_substr((string)($post['name'] ?? $code), 0, 50),
                'status' => (int)($post['status'] ?? 1) === 1 ? 1 : 0,
                'sort' => (int)($post['sort'] ?? 0),
                'config' => $config,
            ];
            if (Db::table($table)->where('code', $code)->find()) {
                Db::table($table)->where('code', $code)->update($data);
            } else {
                Db::table($table)->insert(['code' => $code] + $data);
            }
        } elseif ($op === 'toggle' && $code !== '') {
            $row = Db::table($table)->where('code', $code)->find();
            if ($row) {
                Db::table($table)->where('code', $code)->update(['status' => (int)$row['status'] === 1 ? 0 : 1]);
            }
        } elseif ($op === 'delete' && $code !== '') {
            Db::table($table)->where('code', $code)->delete();
        }
    }

    /** macCMS 支付配置状态条：只显示网关与商户号，绝不回显 key。 */
    private function macPayBox(): string
    {
        $mac = MacPay::epayChannel();
        $body = $mac === null
            ? '<p class="sv-hint">没有检测到 macCMS 的易支付配置。到 macCMS 后台「'
                . self::e(MacPay::configPath()) . '」填好接口地址 / 商户号 / 密钥，插件会自动复用；'
                . '也可以直接在下方新增通道。</p>'
            : $this->macPayDetail($mac);
        return '<div class="sv-card"><div class="sv-section-title">macCMS 在线支付配置</div>' . $body . '</div>';
    }

    private function macPayDetail(array $mac): string
    {
        $cfg = json_decode((string)$mac['config'], true) ?: [];
        $url = (string)($cfg['url'] ?? '');
        $pid = (string)($cfg['pid'] ?? '');
        return '<p class="sv-hint sv-flash-ok">已检测到 macCMS 的易支付配置，插件会直接复用。</p>'
            . '<div class="sv-inline"><span class="sv-code">' . self::e($url) . '</span>'
            . '<span class="sv-code">PID ' . self::e($pid) . '</span>'
            . '<span class="sv-badge ok">密钥已配置</span></div>'
            . '<form method="post" class="sv-actions">' . $this->csrfField()
            . '<input type="hidden" name="op" value="import_mac">'
            . '<button class="sv-btn ghost" type="submit">复制为插件通道</button></form>';
    }

    private function notifyUrl(): string
    {
        $host = (string)($_SERVER['HTTP_HOST'] ?? '');
        if ($host === '') {
            return '/icciu_api.php/v1.Payment/notify';
        }
        $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
            || (int)($_SERVER['SERVER_PORT'] ?? 0) === 443;
        return ($https ? 'https://' : 'http://') . $host . '/icciu_api.php/v1.Payment/notify';
    }
}
