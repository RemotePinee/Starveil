<?php
/**
 * macCMS 插件入口。
 *
 * 命名规则来自 FastAdmin 插件框架：目录名 = 插件名 = 类名（小写目录 `starveilapi` → 类 `Starveilapi`），
 * 文件名也必须与类名一致，否则后台启用时 `get_addon_class()` 找不到类。
 *
 * 与历史插件的差别：
 * - install()/uninstall() 真正干活，不再把逻辑塞在 config.php（历史版本用未定义变量，PHP 8 直接 500）；
 * - 安装/迁移幂等；唯一的 DROP 在 v2.5.4 迁移里（下线两张空表，升级前备份），
 *   卸载才清自己的表（旧 mysql.sql 有 18 条 DROP，进一次配置页就清库）；
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
        // 后台点「卸载」就是卸载：清表、摘快捷菜单、删站点根 icciu_api.php。
        // 想留数据就不要点卸载，先 mysqldump 导出。
        return \Starveil\Api\Installer::uninstall(true);
    }

    /** 插件类被加载时不一定经过 API 入口，这里自己挂自动加载。 */
    private function boot(): void
    {
        require_once __DIR__ . '/src/autoload.php';
    }
}
