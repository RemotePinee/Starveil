<?php
/**
 * macCMS 插件入口。
 *
 * 命名规则来自 FastAdmin 插件框架：目录名 = 插件名 = 类名（小写目录 `starveilapi` → 类 `Starveilapi`），
 * 文件名也必须与类名一致，否则后台启用时 `get_addon_class()` 找不到类。
 *
 * 与历史插件的差别：
 * - install()/uninstall() 真正干活，不再把逻辑塞在 config.php（历史版本用未定义变量，PHP 8 直接 500）；
 * - 安装器幂等，且没有任何 DROP（旧 mysql.sql 有 18 条 DROP，进一次配置页就清库）；
 * - 数据库变更走版本迁移器，不覆盖核心 application/data/update/database.php。
 */

namespace addons\starveilapi;

use think\Addons;

class Starveilapi extends Addons
{
    /** 插件标识，与目录名一致。 */
    public const NAME = 'starveilapi';

    public function install()
    {
        $this->boot();
        return \Starveil\Api\Installer::install();
    }

    public function uninstall()
    {
        $this->boot();
        // 只删自己的表；显式传 false，后台点一次卸载不会直接清数据
        return \Starveil\Api\Installer::uninstall(false);
    }

    /** 插件类被加载时不一定经过 API 入口，这里自己挂自动加载。 */
    private function boot(): void
    {
        require_once __DIR__ . '/src/autoload.php';
    }
}
