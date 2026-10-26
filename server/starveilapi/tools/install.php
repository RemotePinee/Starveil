<?php
/**
 * 补跑插件安装器：建表 + 默认配置 + 菜单，用于「后台显示已启用但表不存在」的情况。
 *
 * 用法（站点根目录）：
 *   php addons/starveilapi/tools/install.php
 *
 * 幂等：建表全部 CREATE TABLE IF NOT EXISTS，已存在的表不会被清空。
 * 只允许 CLI 执行。
 */

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(403);
    exit("请用命令行运行：php addons/starveilapi/tools/install.php\n");
}

$root = dirname(__DIR__, 3) . DIRECTORY_SEPARATOR;

define('ROOT_PATH', $root);
define('APP_PATH', $root . 'application' . DIRECTORY_SEPARATOR);
define('MAC_COMM', APP_PATH . 'common' . DIRECTORY_SEPARATOR . 'common' . DIRECTORY_SEPARATOR);
define('MAC_HOME_COMM', APP_PATH . 'index' . DIRECTORY_SEPARATOR . 'common' . DIRECTORY_SEPARATOR);
define('MAC_ADMIN_COMM', APP_PATH . 'admin' . DIRECTORY_SEPARATOR . 'common' . DIRECTORY_SEPARATOR);
define('MAC_START_TIME', microtime(true));
define('BIND_MODULE', 'api');
define('ENTRANCE', 'api');
define('IN_FILE', '/icciu_api.php');

require $root . 'thinkphp' . DIRECTORY_SEPARATOR . 'base.php';
if (method_exists('\think\App', 'initCommon')) {
    \think\App::initCommon();
} else {
    (new \think\App())->initialize();
}
require $root . 'addons' . DIRECTORY_SEPARATOR . 'starveilapi' . DIRECTORY_SEPARATOR . 'src' . DIRECTORY_SEPARATOR . 'autoload.php';

use Starveil\Api\Installer;

echo "PHP " . PHP_VERSION . "\n";

try {
    $result = Installer::repair();
} catch (\Throwable $e) {
    echo "安装失败：" . $e->getMessage() . "\n";
    exit(1);
}

if ($result !== true) {
    echo "安装失败：\n";
    print_r($result);
    exit(1);
}

echo "安装成功（表结构 / 默认配置 / 菜单）\n";

$prefix = Installer::prefix();
$tables = \think\Db::query('SHOW TABLES LIKE ' . \think\Db::quote($prefix . 'cuican_%'));
echo "数据表：" . count($tables) . " 张（前缀 {$prefix}cuican_）\n";

$order = \think\Db::query('SHOW TABLES LIKE ' . \think\Db::quote($prefix . 'cuican_order'));
echo "订单表：" . (empty($order) ? '缺失' : '存在') . "\n";

foreach (['runtime/cache', 'runtime/temp'] as $dir) {
    $path = $root . str_replace('/', DIRECTORY_SEPARATOR, $dir);
    if (!is_dir($path)) {
        continue;
    }
    foreach (glob($path . DIRECTORY_SEPARATOR . '*') ?: [] as $item) {
        is_dir($item) ? @rmdir($item) : @unlink($item);
    }
}
echo "缓存已清理\n";
