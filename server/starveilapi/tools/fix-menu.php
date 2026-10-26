<?php
/**
 * 把「星幕 API」写进后台左侧快捷菜单（不依赖插件是否启用、不依赖后台按钮）。
 *
 * 用法（在站点根目录、或者任意目录都行）：
 *   php addons/starveilapi/tools/fix-menu.php
 *
 * 只做三件事：合并 quickmenu.php、写一份 txt 兜底、清 runtime 缓存。
 * 不做鉴权，只应该用 CLI 跑，别放到 web 可访问的位置。
 */

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(403);
    exit("请用命令行运行：php addons/starveilapi/tools/fix-menu.php\n");
}

// tools/ 在插件目录下一层：addons/starveilapi/tools → 站点根要往上三级
$root = dirname(__DIR__, 3) . DIRECTORY_SEPARATOR;
$entry = '星幕 API,/addons/starveilapi/admin.php';

$file = $root . 'application/extra/quickmenu.php';
$lines = [];
if (is_file($file)) {
    $loaded = include $file;
    if (is_array($loaded)) {
        $lines = array_values(array_map('strval', $loaded));
    }
}
echo "现有菜单条目：\n";
foreach ($lines as $i => $line) {
    echo "  [$i] $line\n";
}

$lines = array_values(array_filter($lines, static fn (string $l): bool => !str_contains($l, 'starveilapi')));
array_unshift($lines, $entry);

@chmod($file, 0644);
$bytes = @file_put_contents($file, "<?php\nreturn " . var_export($lines, true) . ";\n");
if ($bytes === false) {
    echo "\n写入失败：application/extra 没有写权限。\n";
    echo "执行：chown -R www:www application/extra && chmod 755 application/extra\n";
    exit(1);
}
echo "\n已写入 {$file}（{$bytes} 字节）\n";

// 老版本/配置未加载时会读这个 txt，顺手写一份，保持两处一致
$txtDir = $root . 'application/data/config';
@mkdir($txtDir, 0755, true);
@file_put_contents($txtDir . DIRECTORY_SEPARATOR . 'quickmenu.txt', implode("\r", $lines));

// 清掉模板/配置缓存，避免后台还显示旧菜单
foreach (['runtime/cache', 'runtime/temp'] as $dir) {
    $path = $root . str_replace('/', DIRECTORY_SEPARATOR, $dir);
    if (!is_dir($path)) {
        continue;
    }
    foreach (glob($path . DIRECTORY_SEPARATOR . '*') ?: [] as $item) {
        is_dir($item) ? @rmdir($item) : @unlink($item);
    }
}
echo "缓存已清理\n\n最终内容：\n";
echo (string)@file_get_contents($file);
