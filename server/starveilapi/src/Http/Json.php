<?php
/** 统一响应壳：`{code,msg,data}`，code==200 为成功（与客户端契约一致）。 */

declare(strict_types=1);

namespace Starveil\Api\Http;

final class Json
{
    public static function ok(mixed $data = null, string $msg = 'success'): void
    {
        self::send(200, ['code' => 200, 'msg' => $msg, 'data' => $data], 200);
    }

    /**
     * 业务失败。
     *
     * HTTP 状态固定 200：客户端按响应体里的 `code` 判定成功/失败（后端契约），
     * 而且不少 nginx 开了 `fastcgi_intercept_errors`，PHP 返回 404/500 时
     * 会被换成一张 HTML 错误页，客户端直接解析失败。
     * 需要真实 HTTP 状态码的只有入口级的"站点未安装"（见 entry.php）。
     */
    public static function fail(int $code, string $msg, int $httpStatus = 400): void
    {
        self::send(200, ['code' => $code, 'msg' => $msg, 'data' => null], 200);
    }

    private static function send(int $code, array $payload, int $httpStatus): void
    {
        if (!headers_sent()) {
            http_response_code($httpStatus);
            header('Content-Type: application/json; charset=utf-8');
        }
        echo json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    }
}
