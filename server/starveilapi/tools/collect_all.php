<?php
/**
 * 全量采集 CLI：绕过浏览器，逐页跑采集。
 *
 * 为什么需要它：后台的「采集所有」会在一个请求里跑完全部页码，
 * 几千页下来浏览器先崩。CLI 逐页跑、每页存一次、可中断可续采。
 *
 * 用法（站点根目录）：
 *   php addons/starveilapi/tools/collect_all.php --flag=<cjflag> --url=<cjurl> [选项]
 *
 *   --flag     采集节点指纹，后台「自定义接口」里那条记录的 cjflag
 *   --url      采集地址（原样，不要 URL 编码，脚本自己处理）
 *   --type     接口类型 1=xml 2=json        默认 1
 *   --from     起始页                       默认 1
 *   --to       结束页，0 表示采到最后一页     默认 0
 *   --delay    每页间隔秒数                  默认 0.5
 *   --h        只采最近 N 小时（采集源支持时才有效），留空表示全量
 *   --dry      只打印将要执行的内容，不真正写库
 *   --state    断点文件路径，默认 /tmp/collect_all.state
 *
 * 例：
 *   php addons/starveilapi/tools/collect_all.php \
 *     --flag=e4d4253820ad80ad27b8caa38e0aa80b \
 *     --url='http://154.222.26.37:9527/api.php/provide/vod/' --type=1
 */

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(403);
    exit("请用命令行运行\n");
}

// ── 参数 ────────────────────────────────────────────────────────────────
$opt = getopt('', [
    'flag:', 'url:', 'type::', 'from::', 'to::', 'delay::', 'h::', 'dry', 'state::',
]);

$flag = (string)($opt['flag'] ?? '');
$url = (string)($opt['url'] ?? '');
if ($flag === '' || $url === '') {
    fwrite(STDERR, "缺少 --flag 或 --url，用 -h 之前先看文件头注释\n");
    exit(1);
}
$type = (int)($opt['type'] ?? 1);
$fromPage = max(1, (int)($opt['from'] ?? 1));
$toPage = (int)($opt['to'] ?? 0);
$delayMs = (int)round(((float)($opt['delay'] ?? 0.5)) * 1000);
$hParam = isset($opt['h']) ? trim((string)$opt['h']) : '';
$dry = isset($opt['dry']);
$stateFile = (string)($opt['state'] ?? '/tmp/collect_all.state');

// ── 引导 macCMS ─────────────────────────────────────────────────────────
$root = dirname(__DIR__, 3) . DIRECTORY_SEPARATOR;

define('ROOT_PATH', $root);
define('APP_PATH', $root . 'application' . DIRECTORY_SEPARATOR);
define('MAC_COMM', APP_PATH . 'common' . DIRECTORY_SEPARATOR . 'common' . DIRECTORY_SEPARATOR);
define('MAC_HOME_COMM', APP_PATH . 'index' . DIRECTORY_SEPARATOR . 'common' . DIRECTORY_SEPARATOR);
define('MAC_ADMIN_COMM', APP_PATH . 'admin' . DIRECTORY_SEPARATOR . 'common' . DIRECTORY_SEPARATOR);
define('MAC_START_TIME', microtime(true));
define('BIND_MODULE', 'admin');
define('ENTRANCE', 'admin');
define('IN_FILE', '/admin.php');

require ROOT_PATH . 'thinkphp' . DIRECTORY_SEPARATOR . 'base.php';
if (method_exists('\think\App', 'initCommon')) {
    \think\App::initCommon();
} else {
    (new \think\App())->initialize();
}

// CLI 下没有路由，Request::module() 是空的，model() 助手会去找 app\model\Xxx。
// 把模块手动绑到 admin，它才会走 admin\model -> common\model 的回落链。
\think\Request::instance()->module('admin');

// ── 工具函数 ────────────────────────────────────────────────────────────
function say(string $msg): void
{
    echo '[' . date('H:i:s') . '] ' . $msg . PHP_EOL;
}

function stateRead(string $file): array
{
    return is_file($file) ? (array)(json_decode((string)file_get_contents($file), true) ?: []) : [];
}

function stateWrite(string $file, array $data): void
{
    @file_put_contents($file, json_encode($data, JSON_UNESCAPED_UNICODE));
}

/**
 * 组装一次请求的参数。
 *
 * `ac=cjall` 是「采集所有」，`ac=cj` 是「按参数采」，区别就在 h / ids / wd
 * 这几个过滤项上：给了过滤条件必须用 `cj`，否则会被忽略。
 */
function collectParam(string $flag, string $url, int $type, int $page, string $h): array
{
    return [
        'ac' => $h === '' ? 'cjall' : 'cj',
        'cjflag' => $flag,
        'cjurl' => $url,
        'h' => $h,
        't' => '',
        'ids' => '',
        'wd' => '',
        'type' => (string)$type,
        'mid' => '1',
        'opt' => '0',
        'sync_pic_opt' => '0',
        'filter' => '0',
        'filter_from' => '',
        'filter_year' => '',
        'param' => '',
        'page' => (string)$page,
    ];
}

// ── 主流程 ──────────────────────────────────────────────────────────────
// Collect 在 app\common\model 下，不是 app\model；model() 助手只查后者，直接 new
$collector = new \app\common\model\Collect();

say(sprintf('采集源：%s（type=%d）', $url, $type));
say(sprintf('起始页：%d，结束页：%s', $fromPage, $toPage === 0 ? '自动探测' : (string)$toPage));
if ($hParam !== '') {
    say("时间窗口：最近 {$hParam} 小时（采集源必须支持 h 参数，否则等同全量）");
} else {
    say('时间窗口：不限（全量）');
}
if ($dry) {
    say('*** dry 模式：只探测页数，不写库 ***');
}

$state = stateRead($stateFile);
$page = max($fromPage, (int)($state['next'] ?? $fromPage));
$totalDone = 0;
$consecutiveFail = 0;
$emptyRun = 0;

while (true) {
    $param = collectParam($flag, $url, $type, $page, $hParam);

    $t0 = microtime(true);
    $res = $collector->vod($param);
    $cost = round((microtime(true) - $t0) * 1000);

    if (!is_array($res) || ($res['code'] ?? 0) > 1) {
        $msg = is_array($res) ? ($res['msg'] ?? '未知错误') : '返回非数组';
        say("第 {$page} 页失败：{$msg}");
        // 连续三页失败就停，别在死源上无限重试
        if (++$consecutiveFail >= 3) {
            say('连续 3 页失败，中止。修好之后用 --from=' . $page . ' 续采');
            exit(2);
        }
        $page++;
        usleep($delayMs * 1000);
        continue;
    }
    $consecutiveFail = 0;

    $pageCount = (int)($res['page']['pagecount'] ?? 0);
    $records = (int)($res['page']['recordcount'] ?? 0);
    $rows = is_array($res['data'] ?? null) ? count($res['data']) : 0;

    if ($page === $fromPage || $fromPage === 1) {
        if ($pageCount > 0) {
            say("探测：共 {$pageCount} 页 / {$records} 条");
        }
    }

    if ($dry) {
        say("第 {$page}/{$pageCount} 页：返回 {$rows} 条，{$cost}ms（dry，未写库）");
    } else {
        // 这一句才真正落库；vod() 只负责把远程列表拉回来
        $collector->vod_data($param, $res);
        $state['next'] = $page + 1;
        stateWrite($stateFile, $state);
        say(sprintf('第 %d/%d 页：%d 条，%dms，累计已采 %d 页', $page, $pageCount, $rows, $cost, ++$totalDone));
    }

    if ($toPage > 0 && $page >= $toPage) {
        say("到达指定结束页 {$toPage}，停止");
        break;
    }
    if ($pageCount > 0 && $page >= $pageCount) {
        say('已到最后一页，完成');
        break;
    }
    // 空页不等于到头：采集源按 `has_url` 过滤后有些页码是空的（实测第 930 页
    // 返回 recordcount=25941 却零条），直接停会把后面几百页全漏掉。
    // 只累计连续空页，连到 20 页才认为是真的没有更多数据。
    $emptyRun = ($rows === 0) ? $emptyRun + 1 : 0;
    if ($emptyRun >= 20) {
        say('连续 20 页为空，判定采集完毕');
        break;
    }

    $page++;
    usleep($delayMs * 1000);
}

say('结束。断点文件：' . $stateFile);
