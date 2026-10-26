<?php
/**
 * 写操作的统一应答：校验失败、JSON 成功/失败、是否 AJAX。
 *
 * 后台是 AJAX 提交：`header('Location')` 前端不会跟，它会一直拿到 admin.php
 * 最后那句 `{"ok":true,"msg":"已保存"}`——校验等于没做。所以校验失败必须直接回 JSON，
 * 前端见到 ok=false 才弹红字且不刷新。这段判断原来在 Actions / HomePage / FeaturedPage
 * 各有一份，现在只留这里一份。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

final class Guard
{
    /**
     * 校验不通过时统一从这里退出：把原因带回列表页，一条都不写库。
     *
     * @param array<string,string|int> $extra 额外带回的 query（如热度榜的 type_id）
     * @return never
     */
    public static function fail(string $page, string $message, array $extra = []): void
    {
        if (self::isAjaxRequest()) {
            self::jsonError($message);
        }
        $query = ['a' => $page] + $extra + ['error' => $message];
        header('Location: ?' . http_build_query($query));
        exit;
    }

    /** 当前是不是后台的 AJAX 提交（前端用 `?ajax=1` 拉页面与提交表单）。 */
    public static function isAjaxRequest(): bool
    {
        return (string)($_GET['ajax'] ?? '') === '1'
            || strtolower((string)($_SERVER['HTTP_X_REQUESTED_WITH'] ?? '')) === 'xmlhttprequest';
    }

    /**
     * 校验失败的统一响应：422 + `{ok:false,msg}`，前端据此弹红字并停在原页面。
     *
     * @return never
     */
    public static function jsonError(string $message): void
    {
        http_response_code(422);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['ok' => false, 'msg' => $message], JSON_UNESCAPED_UNICODE);
        exit;
    }

    /**
     * 操作成功的统一响应：`{ok:true,msg}`，前端弹绿字后自动刷新当前页。
     *
     * @return never
     */
    public static function jsonOk(string $message): void
    {
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['ok' => true, 'msg' => $message], JSON_UNESCAPED_UNICODE);
        exit;
    }
}
