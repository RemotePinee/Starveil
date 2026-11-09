<?php
/**
 * 后台「版本更新」页：维护 `v1.main/version` 下发的 APK 版本信息。
 *
 * 为什么不并进「运营设置」：那页的字段表（SettingsSchema）是启动配置，
 * 版本信息只在发版前后改一次，单独一页更好找，也不会被别的设置表单一起提交。
 *
 * 值存 `cuican_setting` 而不是插件代码：覆盖升级会整目录替换 PHP 文件，
 * 写死在代码里的版本号会被旧值覆盖回"已配"，运营就得每次升级后重填一遍。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Admin\Ui\Alert;
use Starveil\Api\Service\Update;
use Starveil\Api\Support\Guard;
use Starveil\Api\Support\Setting;

final class VersionPage extends BasePage
{
    /** 表单字段 → setting 键。渲染与保存都读这张表，键名不会漂移。 */
    private const FIELDS = [
        'code' => Update::KEY_CODE,
        'name' => Update::KEY_NAME,
        'url' => Update::KEY_URL,
        'sha256' => Update::KEY_SHA256,
        'force' => Update::KEY_FORCE,
        'changelog' => Update::KEY_CHANGELOG,
    ];

    /** @return array{title:string,html:string} */
    public function render(): array
    {
        return ['title' => '版本更新', 'html' => $this->flash() . $this->form() . $this->preview()];
    }

    /** 校验失败由 Guard::fail 重定向回来，错误串在 `?error=` 里；不显示出来就是"点了没反应"。 */
    private function flash(): string
    {
        $error = trim((string)($_GET['error'] ?? ''));
        return $error === '' ? '' : Alert::error($error);
    }

    public function mutate(array $post): void
    {
        if ((string)($post['op'] ?? '') !== 'save') {
            return;
        }

        $code = (int)($post['code'] ?? 0);
        if ($code < 0) {
            Guard::fail('version', '版本号不能为负数');
        }

        $url = trim((string)($post['url'] ?? ''));
        // 客户端只接受 https 下载地址；http 地址会在下载阶段静默失败，不如保存时就拦下
        if ($url !== '' && !preg_match('#^https://#i', $url)) {
            Guard::fail('version', 'APK 地址必须是 https:// 开头（客户端只接受 https）');
        }

        $sha = strtolower(trim((string)($post['sha256'] ?? '')));
        if ($sha !== '' && !preg_match('/^[0-9a-f]{64}$/', $sha)) {
            Guard::fail('version', 'sha256 必须是 64 位十六进制（留空表示客户端跳过校验）');
        }

        // 有版本号却没地址，客户端会弹一个点不动的更新框，比不提示更糟
        if ($code > 0 && $url === '') {
            Guard::fail('version', '填了版本号就必须填 APK 地址');
        }

        Setting::set(self::FIELDS['code'], (string)$code);
        Setting::set(self::FIELDS['name'], trim((string)($post['name'] ?? '')));
        Setting::set(self::FIELDS['url'], $url);
        Setting::set(self::FIELDS['sha256'], $sha);
        Setting::set(self::FIELDS['force'], (string)($post['force'] ?? '0') === '1' ? '1' : '0');
        Setting::set(self::FIELDS['changelog'], trim((string)($post['changelog'] ?? '')));
    }

    // ---- 表单 ----------------------------------------------------------

    private function form(): string
    {
        $v = Update::payload();

        return $this->card(
            '在线更新（APK）',
            '<p class="sv-hint">客户端拿下发的 <b>code</b> 与本机版本号比较，大于本机才会提示更新；'
            . '<b>code = 0 表示没配</b>，客户端直接跳过检查。改版本时先传 APK，再回来改这里。</p>'
            . '<form method="post">' . $this->csrfField()
            . '<input type="hidden" name="op" value="save"><div class="sv-grid">'
            . $this->field('版本号', 'code', (string)$v['code'], '整数，例如 27；要比客户端当前版本号大')
            . $this->field('版本名', 'name', $v['name'], '给用户看的，例如 2.7.0')
            . $this->field('APK 地址', 'url', $v['url'], 'https:// 开头；填了版本号就必填')
            . $this->field('sha256', 'sha256', $v['sha256'], '64 位十六进制；留空则客户端跳过文件校验')
            . $this->selectField(
                '强制更新',
                'force',
                ['0' => '否', '1' => '是（弹窗不可关，只能下载）'],
                $v['force'] ? '1' : '0'
            )
            . $this->textarea('更新说明', 'changelog', $v['changelog'], '客户端更新弹窗里显示，一行一条')
            . '</div><div class="sv-actions"><button class="sv-btn" type="submit">保存</button>'
            . '</div></form>'
        );
    }

    /**
     * 把「客户端实际会收到什么」原样摆出来。
     *
     * 字段填错时（比如 code 忘了改、地址填成 http）这里一眼能看出来，
     * 省得装个 APK 去猜。
     */
    private function preview(): string
    {
        $json = json_encode(
            Update::payload(),
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT
        );

        return $this->card(
            '当前下发（GET v1.main/version）',
            '<pre class="sv-code">' . self::e((string)$json) . '</pre>'
        );
    }
}
