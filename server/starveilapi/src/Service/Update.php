<?php
/**
 * 在线更新：`v1.main/version` 下发的 APK 版本信息。
 *
 * 数据放 `cuican_setting`（后台「版本更新」页维护），不写进插件 PHP：
 * 覆盖升级会整目录替换，写死在代码里的版本号会被旧值覆盖回「已配」，
 * 而 setting 表里的值不会被插件文件覆盖。同一个理由也适用于 APK 地址与 sha256。
 *
 * 没配时 `code = 0`，客户端据此跳过更新（见 VersionDto 的注释），
 * 所以这里不需要「默认版本」——空就是没配，不能瞎猜一个 code。
 */

declare(strict_types=1);

namespace Starveil\Api\Service;

use Starveil\Api\Support\Setting;

final class Update
{
    /**
     * setting 键名集中在这里：后台写、接口读都用这几个常量。
     *
     * 键名一旦写错，表现是"后台保存成功、客户端永远不提示更新"，很难定位，
     * 所以不允许页面里再手写一份字符串。
     */
    public const KEY_CODE = 'app_version_code';
    public const KEY_NAME = 'app_version_name';
    public const KEY_URL = 'app_version_url';
    public const KEY_SHA256 = 'app_version_sha256';
    public const KEY_FORCE = 'app_version_force';
    public const KEY_CHANGELOG = 'app_version_changelog';

    /** @return array{code:int,name:string,url:string,sha256:string,force:bool,changelog:string} */
    public static function payload(): array
    {
        return [
            // 与客户端 BuildConfig.VERSION_CODE 比较，大于当前值才提示更新
            'code' => Setting::int(self::KEY_CODE, 0),
            'name' => Setting::get(self::KEY_NAME),
            'url' => Setting::get(self::KEY_URL),
            // 后台可能填大写，客户端按小写比对文件摘要，统一转小写
            'sha256' => strtolower(Setting::get(self::KEY_SHA256)),
            'force' => Setting::get(self::KEY_FORCE) === '1',
            'changelog' => Setting::get(self::KEY_CHANGELOG),
        ];
    }
}
