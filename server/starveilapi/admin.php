<?php
/**
 * 插件后台。独立入口：`/addons/starveilapi/admin.php`
 *
 * 为什么不用 FastAdmin 的 addon 路由：macCMS 默认关掉 rewrite（`url_route_on=false`），
 * 走 `/addons/xxx/...` 还得在 app_init 钩子里临时开路由，插件被禁用时行为很脆。
 * 用一个独立入口，复用 macCMS 的后台登录态（`model('Admin')->checkLogin()`），
 * 不依赖伪静态。
 *
 * 安全：
 * - 非 CLI 一律校验后台登录；
 * - 所有写操作要求 POST + CSRF token；
 * - 输出全部 htmlspecialchars，SQL 全部走查询构造器；
 * - CLI 模式只用于本地自检（`php admin.php dashboard`），无 HTTP 暴露。
 */

declare(strict_types=1);

header('Content-Type: text/html; charset=utf-8');

$isCli = PHP_SAPI === 'cli';
if (!$isCli) {
    // 入口在 addons 下，站点根在两级之上
    $root = dirname(__DIR__, 2) . DIRECTORY_SEPARATOR;
} else {
    $root = dirname(__DIR__, 2) . DIRECTORY_SEPARATOR;
}

define('ROOT_PATH', $root);
define('APP_PATH', $root . 'application/');
define('MAC_COMM', $root . 'application/common/common/');
define('MAC_HOME_COMM', $root . 'application/index/common/');
define('MAC_ADMIN_COMM', $root . 'application/admin/common/');
define('MAC_START_TIME', microtime(true));
define('BIND_MODULE', 'admin');
define('ENTRANCE', 'admin');
define('IN_FILE', '/admin.php');

$lib = __DIR__ . '/src/autoload.php';
if (!is_file($lib)) {
    exit('插件文件不完整：缺少 src/autoload.php');
}

require $root . 'thinkphp/base.php';
if (method_exists('\think\App', 'initCommon')) {
    \think\App::initCommon();
} else {
    (new \think\App())->initialize();
}
require $lib;

// 自愈：打开一次插件后台就把左侧快捷菜单补上。
// 不依赖"禁用/启用"（有些服务器上禁用按钮会因写权限失败），也不改核心代码。
try {
    \Starveil\Api\Installer::registerMenu();
} catch (\Throwable $e) {
    // 菜单写不进去不影响后台本身，页面继续渲染
}

use Starveil\Api\Admin\Actions;
use Starveil\Api\Admin\View;

if (!$isCli) {
    // model('Admin') 在 admin 模块里会解析成 appdmin\model\Admin（不存在），
    // 必须显式用公共模型；任何异常都当"未登录"处理，不能把错误页甩给用户。
    try {
        $login = (new \app\common\model\Admin())->checkLogin();
    } catch (\Throwable $e) {
        $login = null;
    }
    if (!is_array($login) || (int)($login['code'] ?? 0) !== 1) {
        http_response_code(403);
        echo '<!doctype html><meta charset="utf-8"><p>请先登录后台：'
            . '<a href="/admin.php">前往登录</a></p>';
        exit;
    }
    // 插件级权限：macCMS 的 checkLogin 只回答"有个管理员登录了"，任何子管理员都能
    // 打开本插件——读商户 KEY、审提现、导卡密。这里再收一层：超管（admin_id=1）
    // 或 admin_auth 里显式含 starveilapi 的账号才放行。
    // admin_auth 是逗号分隔的「控制器/动作」串，
    // 超管登录时它可能是 all 或空，所以超管单独用 admin_id 判定。
    $admin = is_array($login['info'] ?? null) ? $login['info'] : [];
    $isSuper = (string)($admin['admin_id'] ?? '') === '1';
    if (!$isSuper && strpos((string)($admin['admin_auth'] ?? ''), 'starveilapi') === false) {
        http_response_code(403);
        echo '<!doctype html><meta charset="utf-8"><p>该账号没有「星幕 API」的访问权限。'
            . '请改用超级管理员登录，或在后台「系统 → 管理员」里给这个账号勾选 starveilapi 的权限项。</p>';
        exit;
    }
}

// 后台页面自愈：表被删/首次启用没跑成功时，管理员打开插件页就把表补上。
// 放在登录校验之后，未登录请求不会触发任何 DDL。
// 覆盖升级后打开后台就补齐新表/新列（autoMigrate 内部带版本文件缓存，
// 命中时不查库；未命中才跑一次幂等迁移）
try {
    \Starveil\Api\Installer::autoMigrate();
} catch (\Throwable $e) {
    // 迁移失败留给下面的 1146 兜底提示
}

// 根入口缺失时顺手补上（只创建，不覆盖）
if (!is_file(ROOT_PATH . 'icciu_api.php')) {
    try {
        \Starveil\Api\Installer::ensureRootEntry();
    } catch (\Throwable $e) {
        // 没权限写站点根就交给部署文档处理
    }
}

if (!$isCli && !\Starveil\Api\Installer::tablesReady()) {
    try {
        \Starveil\Api\Installer::repair();
    } catch (\Throwable $e) {
        // 修不了就让下面的 Actions 报 1146，页面里会提示跑 tools/install.php
    }
}

// CSRF：token 存在 macCMS 的 session 里
if (!$isCli && session('starveil_csrf') === null) {
    session('starveil_csrf', bin2hex(random_bytes(16)));
}
$csrf = $isCli ? 'cli' : (string)session('starveil_csrf');

$page = $isCli
    ? (string)($argv[1] ?? 'dashboard')
    : (string)($_GET['a'] ?? 'dashboard');

$isAjax = !$isCli && (
    ($_GET['ajax'] ?? '') === '1'
    || strtolower((string)($_SERVER['HTTP_X_REQUESTED_WITH'] ?? '')) === 'xmlhttprequest'
);

if (!$isCli && ($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST'
    && !hash_equals($csrf, (string)($_POST['_token'] ?? ''))) {
    http_response_code(400);
    if ($isAjax) {
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['ok' => false, 'msg' => '页面已过期，请刷新后重试'], JSON_UNESCAPED_UNICODE);
    } else {
        echo '<p>CSRF 校验失败，请刷新页面重试。</p>';
    }
    exit;
}

// 卡密导出：GET 直出 CSV，不能经过 View::layout，否则正文外会多包一层 HTML
if (!$isCli && $page === 'cards' && ($_GET['export'] ?? '') === '1') {
    (new Actions($isCli, $csrf))->exportCards();
    exit;
}

// CLI 自检时可以带一段 query 形式的数据触发写操作：php admin.php cards post "count=2&value=30"
$post = $_POST;
if ($isCli && (($argv[2] ?? '') === 'post') && isset($argv[3])) {
    parse_str((string)$argv[3], $post);
}

// CLI 自检导出：php admin.php cards export（同样直出 CSV，用于测试脚本断言表头）
if ($isCli && ($argv[2] ?? '') === 'export') {
    (new Actions(true, $csrf))->exportCards();
    exit;
}

$actions = new Actions($isCli, $csrf);
try {
    $result = $actions->handle($page, $post);
} catch (\Throwable $e) {
    // 表还没建时不要甩一屏 SQL 错误，直接给可执行的下一步
    $message = str_contains($e->getMessage(), '1146')
        ? '插件数据表还没建：打开一次插件后台会自动补建，仍失败再看 tools/install.php'
        : $e->getMessage();
    http_response_code(500);
    // AJAX 必须回 JSON：回一屏 HTML 时前端 `r.json()` 直接失败，
    // 用户只看到"网络错误，请重试"，真实原因被吞掉（排查时等于瞎了）
    if ($isAjax) {
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['ok' => false, 'msg' => $message], JSON_UNESCAPED_UNICODE);
        exit;
    }
    echo '<pre style="padding:16px;white-space:pre-wrap">'
        . htmlspecialchars($message, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') . '</pre>';
    exit;
}
if ($isCli) {
    echo strip_tags($result['html']);
    exit;
}

$isPostRequest = ($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST';
if ($isPostRequest) {
    if ($isAjax) {
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['ok' => true, 'msg' => '已保存'], JSON_UNESCAPED_UNICODE);
        exit;
    }
    // 非 AJAX 表单：保存后重定向，避免刷新重复提交
    header('Location: ?a=' . urlencode($page));
    exit;
}

if ($isAjax) {
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode([
        'ok' => true,
        'page' => $page,
        'title' => $result['title'],
        'html' => $result['html'],
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

echo View::layout($page, $result['title'], $result['html'], $csrf);
