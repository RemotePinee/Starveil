<?php
/**
 * API 引导。由站点根的 `icciu_api.php` 转发进来，本身不含业务。
 *
 * 为什么拆两层：站点根那个文件必须存在（客户端固定请求 `/icciu_api.php/v1...`），
 * 但插件升级不该要求运营再替换一次根文件。所以根文件只负责 require 本文件，
 * 升级只覆盖 addons/starveilapi/，根文件永远不用动。
 */

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');

error_reporting(E_ALL);
ini_set('display_errors', '0');

if (PHP_VERSION_ID < 80000) {
    http_response_code(500);
    echo json_encode(['code' => 500, 'msg' => 'PHP 版本过低，至少 8.0'], JSON_UNESCAPED_UNICODE);
    exit;
}

// 本文件在 addons/starveilapi/src/ 下，向上找站点根（不依赖 cwd，也不依赖被谁引用）
$root = dirname(__DIR__, 3) . '/';
for ($i = 0; $i < 3; $i++) {
    if (is_dir($root . 'application') && is_file($root . 'thinkphp/base.php')) {
        break;
    }
    $root = dirname($root) . '/';
}
define('ROOT_PATH', $root);
define('APP_PATH', $root . 'application/');
define('MAC_COMM', $root . 'application/common/common/');
define('MAC_HOME_COMM', $root . 'application/index/common/');
define('MAC_ADMIN_COMM', $root . 'application/admin/common/');
define('MAC_START_TIME', microtime(true));
define('BIND_MODULE', 'api');
define('ENTRANCE', 'api');
define('IN_FILE', '/icciu_api.php');

if (!is_file($root . 'application/data/install/install.lock')) {
    http_response_code(503);
    echo json_encode(['code' => 503, 'msg' => '站点尚未安装'], JSON_UNESCAPED_UNICODE);
    exit;
}

// 只加载 macCMS 框架（配置、Db、缓存），不触发它自己的模块分发
require $root . 'thinkphp/base.php';
if (method_exists('\think\App', 'initCommon')) {
    \think\App::initCommon();
} else {
    (new \think\App())->initialize();
}

require $root . 'addons/starveilapi/src/autoload.php';

// 覆盖升级后第一次请求就把新表/新列补上：不用再让运营跑 tools/install.php。
// 内部先看 runtime 下的版本文件，命中就直接返回，正常请求不查库。
try {
    \Starveil\Api\Installer::autoMigrate();
} catch (\Throwable $e) {
    // 迁移失败不挡接口：具体报错由 Controller 层的 1146 提示给出
}

\Starveil\Api\Kernel::run();
