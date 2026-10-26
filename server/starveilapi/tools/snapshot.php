<?php
/**
 * 后台渲染快照：把 17 个后台页面渲染成完整 HTML，逐页记录 sha256。
 *
 * 用途只有一个——重构前后逐页对比，证明「只搬位置、没改输出」。
 * 不是测试框架，也不写库：所有页面都以 CLI 模式渲染（无登录、无 CSRF、无 AJAX）。
 *
 * 用法（站点根目录）：
 *   php addons/starveilapi/tools/snapshot.php --dump=DIR      每页写一个 <page>.html 到 DIR
 *   php addons/starveilapi/tools/snapshot.php --save=FILE     把各页 sha256 写进 FILE（JSON）
 *   php addons/starveilapi/tools/snapshot.php --check=FILE    与 FILE 里的基线比对，不一致退出码 1
 *   不带参数等同于 --save 到标准输出
 *
 * 标准化（否则每跑一次都不一样）：
 * - 折叠空白（含换行/制表），HTML 缩进不参与比对；
 * - 标签内属性按名排序、class 值按字母排序；
 * - 资源版本号 `?v=<filemtime>` 归一成 `?v=0`（改一行 CSS 就会变，不属于内容差异）；
 * - CLI 模式下 CSRF 固定是 `cli`，不需要额外脱敏。
 *
 * 页面里的数字来自数据库（订单数、今日核销等），换库或跨天跑出来的基线会不同：
 * 基线只在同一份数据库上做前后对比有意义。
 */

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(403);
    exit("请用命令行运行：php addons/starveilapi/tools/snapshot.php\n");
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
require dirname(__DIR__) . DIRECTORY_SEPARATOR . 'src' . DIRECTORY_SEPARATOR . 'autoload.php';

use Starveil\Api\Admin\Actions;
use Starveil\Api\Admin\View;

/** 后台全部页面键（含首页装修的 7 个子页）。 */
const SNAPSHOT_PAGES = [
    'dashboard',
    'settings',
    'cards',
    'topics',
    'live',
    'pay',
    'withdraw',
    'orders',
    'users',
    'banner',
    'featured',
    'hot',
    'guess',
    'blocks',
    'links',
    'tabs',
    'push',
];

/**
 * 把一段 HTML 归一成可比较的形式。
 *
 * 只做「不影响渲染」的整理：空白、属性顺序、class 顺序、资源版本号。
 * 任何标签、文本、属性值的改动都会保留下来。
 */
function snapshot_normalize(string $html): string
{
    // 版本号跟着文件 mtime 走，改了 CSS 就会变，比对时不算差异
    $html = (string)preg_replace('/(\?v=)\d+/', '${1}0', $html);
    // 标签内属性：按名排序，class 值按字母排序
    $html = (string)preg_replace_callback('/<([a-zA-Z][a-zA-Z0-9:-]*)([^<>]*)>/', static function (array $m): string {
        $name = $m[1];
        $raw = $m[2];
        if (trim($raw) === '') {
            return '<' . $name . '>';
        }
        preg_match_all(
            '/([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\s*=\s*"([^"]*)")?/',
            $raw,
            $found,
            PREG_SET_ORDER,
        );
        $pairs = [];
        foreach ($found as $item) {
            $key = strtolower($item[1]);
            $value = $item[2] ?? null;
            if ($key === 'class' && $value !== null) {
                $parts = preg_split('/\s+/', trim($value)) ?: [];
                sort($parts);
                $value = implode(' ', $parts);
            }
            $pairs[$key] = $value === null ? $key : $key . '="' . $value . '"';
        }
        ksort($pairs);
        return '<' . $name . ' ' . implode(' ', $pairs) . '>';
    }, $html);
    // 空白折叠：HTML 里的换行与缩进不参与比对
    $html = (string)preg_replace('/[\r\n\t]+/', ' ', $html);
    $html = (string)preg_replace('/ {2,}/', ' ', $html);
    return trim($html);
}

/** 渲染一页，返回标准化后的完整 HTML。 */
function snapshot_render(string $page): string
{
    $actions = new Actions(true, 'cli');
    $result = $actions->handle($page, []);
    $layout = View::layout($page, (string)$result['title'], (string)$result['html'], 'cli');
    return snapshot_normalize($layout);
}

$args = array_slice($argv, 1);
$dumpTo = '';
$saveTo = '';
$checkTo = '';
foreach ($args as $arg) {
    if (str_starts_with($arg, '--dump=')) {
        $dumpTo = substr($arg, 7);
    } elseif (str_starts_with($arg, '--save=')) {
        $saveTo = substr($arg, 7);
    } elseif (str_starts_with($arg, '--check=')) {
        $checkTo = substr($arg, 8);
    }
}

$hashes = [];
$bodies = [];
foreach (SNAPSHOT_PAGES as $page) {
    try {
        $body = snapshot_render($page);
    } catch (\Throwable $e) {
        fwrite(STDERR, 'FAIL ' . $page . ' <- ' . $e->getMessage() . "\n");
        exit(1);
    }
    if ($body === '') {
        fwrite(STDERR, 'FAIL ' . $page . " <- 空输出\n");
        exit(1);
    }
    $hashes[$page] = hash('sha256', $body);
    $bodies[$page] = $body;
}

if ($dumpTo !== '') {
    if (!is_dir($dumpTo) && !mkdir($dumpTo, 0777, true) && !is_dir($dumpTo)) {
        fwrite(STDERR, '无法创建目录：' . $dumpTo . "\n");
        exit(1);
    }
    foreach ($bodies as $page => $body) {
        file_put_contents(rtrim($dumpTo, '/\\') . DIRECTORY_SEPARATOR . $page . '.html', $body);
    }
    echo '已写出 ' . count($bodies) . ' 页到 ' . $dumpTo . "\n";
    if ($checkTo === '' && $saveTo === '') {
        exit(0);
    }
}

if ($checkTo !== '') {
    $raw = is_file($checkTo) ? (string)file_get_contents($checkTo) : '';
    $baseline = json_decode($raw, true);
    if (!is_array($baseline)) {
        fwrite(STDERR, '基线不可读：' . $checkTo . "\n");
        exit(1);
    }
    $bad = 0;
    foreach ($hashes as $page => $hash) {
        $expect = (string)($baseline[$page] ?? '');
        if ($expect === $hash) {
            echo 'SAME  ' . $page . "\n";
            continue;
        }
        $bad++;
        echo 'DIFF  ' . $page . '  ' . substr($expect === '' ? '(缺失)' : $expect, 0, 12)
            . ' -> ' . substr($hash, 0, 12) . "\n";
    }
    echo '---- 不一致 ' . $bad . ' 页 / 共 ' . count($hashes) . " 页\n";
    exit($bad === 0 ? 0 : 1);
}

if ($saveTo !== '') {
    $json = json_encode($hashes, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . "\n";
    if (file_put_contents($saveTo, $json) === false) {
        fwrite(STDERR, '写入失败：' . $saveTo . "\n");
        exit(1);
    }
    echo '基线已写入 ' . $saveTo . '（' . count($hashes) . " 页）\n";
    exit(0);
}

echo json_encode($hashes, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . "\n";
