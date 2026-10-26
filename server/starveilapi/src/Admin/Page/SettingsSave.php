<?php
/**
 * 运营设置的写操作：解析配置、分组设置、图片上传。
 *
 * 键集合全部来自 [SettingsSchema]，与渲染用的是同一份，不会出现"画得出但存不下"的项。
 */

declare(strict_types=1);

namespace Starveil\Api\Admin\Page;

use Starveil\Api\Admin\HomePage;
use Starveil\Api\Support\Guard;
use Starveil\Api\Support\Media;
use Starveil\Api\Support\Setting;

final class SettingsSave extends BasePage
{
    /** 设置页没有自己的 render，写操作由 [SettingsPage] 调用；这里只留契约形状。 */
    public function render(): array
    {
        return ['title' => '运营设置', 'html' => ''];
    }

    public function mutate(array $post): void
    {
        $this->settingsAction($post);
    }

    public function savePlayer(array $post): void
    {
        $config = [
            'parse2' => trim((string)($post['parse2'] ?? '')),
            'features' => trim((string)($post['features'] ?? '')),
            'issethead' => trim((string)($post['issethead'] ?? '')),
            'headers' => trim((string)($post['headers'] ?? '')),
        ];
        Setting::set('player_info', json_encode($config, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
    }

    /**
     * 运营设置提交的总入口：先接住所有文件上传（图片 + 激励视频），再把文本值写库。
     *
     * 顺序不能反——上传写的是 setting 键，先写文本再用文本框的旧值覆盖，
     * 运营点了"选择文件"也会毫无效果。
     */
    public function settingsAction(array $post): void
    {
        $memberWasOn = Setting::int('member_enabled', 1) === 1;
        $uploaded = $this->saveImageUploads();
        // 视频与图片同一套顺序：先落盘写 setting 键，再让文本框的值进来
        $uploaded = array_merge($uploaded, $this->saveVideoUploads());
        $this->saveSettings($post, $uploaded);
        // 会员体系这一刻从关到开：会员入口会占掉底栏一个名额，原来勾满 5 个就超了。
        // 客户端只画前 5 个，运营勾的最后一栏会凭空消失，所以这里立刻收敛并回一句提示。
        if (!$memberWasOn && Setting::int('member_enabled', 1) === 1) {
            $dropped = HomePage::syncMembershipTabs();
            if ($dropped !== [] && Guard::isAjaxRequest()) {
                Guard::jsonOk('已保存；会员入口固定占一个位置，已把「'
                    . implode('」「', $dropped) . '」移出底栏');
            }
        }
    }

    /**
     * 直投激励视频上传，返回"本次真的传成功"的键。
     *
     * 不并入 [saveImageUploads]：那条路走 macCMS 的 Upload 模型，按图片规则校验
     * （扩展名白名单 + `getimagesize`），mp4 必然失败；视频改用 `Media::uploadVideo`
     * 自己校验扩展名 + finfo MIME 后落盘，见那里的说明。
     *
     * 传失败时保持旧值不动：外链存在另一个键（`ad_reward_video`），不会被这次失败清掉。
     *
     * @return array<int,string>
     */
    private function saveVideoUploads(): array
    {
        if ($this->isCli) {
            return [];
        }
        $done = [];
        foreach (SettingsSchema::VIDEO as $key => $input) {
            $file = Media::uploadVideo($input, 'ad');
            if ($file === null) {
                continue;
            }
            Setting::set($key, $file);
            $done[] = $key;
        }
        return $done;
    }

    /**
     * 处理本组里所有图片上传，返回"本次真的传成功"的键。
     *
     * 上传走 macCMS 的 Upload 模型：本地/云存储、格式校验、水印都由 CMS 负责，
     * 插件只负责把返回的库内路径写进 Setting。失败时不写库、也不报 500，
     * 页面刷新后旧图仍在，运营重试即可。
     *
     * @return array<int,string>
     */
    private function saveImageUploads(): array
    {
        if ($this->isCli) {
            return [];
        }
        $done = [];
        foreach (SettingsSchema::IMAGE as $key => $input) {
            // 统一走 Media::upload：源要求传的 imgdata、按入口解析上传模型、
            // 落盘后的等比瘦身都在那里实现，这里只负责写值
            $file = Media::upload($input, 'ad');
            if ($file === null) {
                continue;
            }
            Setting::set($key, $file);
            $done[] = $key;
        }
        return $done;
    }

    private function saveSettings(array $post, array $uploaded = []): void
    {
        foreach ((array)($post['s'] ?? []) as $key => $value) {
            $key = (string)$key;
            // 这一项本次上传成功过：文本框里的旧值不能再把它盖回去
            if (in_array($key, $uploaded, true)) {
                continue;
            }
            foreach (SettingsSchema::GROUPS as $fields) {
                if (!array_key_exists($key, $fields)) {
                    continue;
                }
                // 多选先处理：这类值本来就是数组，落成字符串会把选项名丢掉
                if (isset(SettingsSchema::MULTI[$key])) {
                    $chosen = array_values(array_intersect(
                        array_map('strval', (array)$value),
                        array_keys(SettingsSchema::MULTI[$key]),
                    ));
                    // 全不选等于把所有登录/注册方式关死，客户端会无路可走：
                    // 直接跳过保存，保留上一次的有效组合
                    if ($chosen !== []) {
                        Setting::set($key, json_encode($chosen, JSON_UNESCAPED_UNICODE));
                    }
                    continue;
                }
                $value = mb_substr((string)$value, 0, 4000);
                // 单选只接受表里列出的键：布尔项误填 "true" 会回退到第一个键（"1"），
                // 枚举项（login_default）填了不存在的值会回退到默认项
                if (isset(SettingsSchema::SELECT[$key])) {
                    $options = SettingsSchema::SELECT[$key];
                    $value = array_key_exists($value, $options)
                        ? $value
                        : (string)array_key_first($options);
                }
                // 敏感项留空表示保持原值：否则运营保存别的设置就会顺手清掉密钥
                if ($value === '' && in_array($key, SettingsSchema::SENSITIVE, true)) {
                    continue;
                }
                Setting::set($key, $value);
            }
        }
    }
}
