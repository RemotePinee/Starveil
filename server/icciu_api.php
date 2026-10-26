<?php
/**
 * 星幕 API 入口。
 *
 * 放在**站点根**，与 `addons/` 同级（和 index.php / admin.php 并列）。
 * 真正的引导在插件里（addons/starveilapi/src/entry.php），
 * 所以插件升级只覆盖 addons 目录，本文件不需要再动。
 */

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');

$bootstrap = __DIR__ . '/addons/starveilapi/src/entry.php';
if (!is_file($bootstrap)) {
    http_response_code(500);
    echo json_encode(['code' => 500, 'msg' => '星幕 API 插件未安装，或 addons/starveilapi 路径不对'], JSON_UNESCAPED_UNICODE);
    exit;
}

require $bootstrap;
