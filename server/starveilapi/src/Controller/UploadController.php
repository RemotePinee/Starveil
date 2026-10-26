<?php
/**
 * 上传：用户头像、提现收款码。
 *
 * （`v1/Upload.php`）有三处洞：GET 版无鉴权、`flag` 参数直接拼路径、
 * 出错时 `var_dump` 破坏 JSON。这里全部改掉：
 * - 必须登录；
 * - 只接受图片，扩展名由服务端按 MIME 决定，不吃客户端文件名；
 * - 路径只由「固定子目录 + user_id + 时间戳」拼成，不存在路径穿越面；
 * - 失败只回 JSON。
 */

declare(strict_types=1);

namespace Starveil\Api\Controller;

use Starveil\Api\Http\Json;
use Starveil\Api\Repo\Users;

final class UploadController extends BaseController
{
    private const MAX_BYTES = 2 * 1024 * 1024;

    /**
     * 落盘扩展名按真实图片类型给。
     *
     * 只判 JPG/PNG/WEBP 却一律写 `.jpg`，WEBP 传上去会以一个声明为
     * jpeg 的扩展名被 nginx 下发，个别客户端解码失败；这里顺手按类型命名。
     */
    private const EXTENSIONS = [
        IMAGETYPE_JPEG => '.jpg',
        IMAGETYPE_PNG => '.png',
        IMAGETYPE_WEBP => '.webp',
    ];

    /** `POST v1.upload/user`，multipart 字段名 `file`。 */
    public function postUser(): void
    {
        $user = $this->requireLogin();
        $relative = $this->storeImage((int)$user['user_id'], 'avatar');
        if ($relative === null) {
            return; // storeImage 已经回过失败 JSON
        }
        (new Users())->updateAllowed((int)$user['user_id'], ['user_portrait' => $relative]);
        Json::ok(['file' => $relative], '上传成功');
    }

    /**
     * `POST v1.upload/withdraw`，multipart 字段名 `file`。
     *
     * 收款码只回路径，不落库：提现申请还没提交时没有任何一行可以挂，
     * 客户端把 `data.file` 原样放进 `v1.user/goldWithdrawApply` 的 `qrcode`。
     */
    public function postWithdraw(): void
    {
        $user = $this->requireLogin();
        $relative = $this->storeImage((int)$user['user_id'], 'withdraw');
        if ($relative === null) {
            return;
        }
        Json::ok(['file' => $relative], '上传成功');
    }

    /**
     * 两个上传接口共用的落盘逻辑：校验 → 建目录 → `{user_id}_{time}.ext`。
     *
     * 返回可直接回给客户端的相对路径；校验或落盘失败时已回 JSON 并返回 null，
     * 调用方只要 `if ($relative === null) return;` 即可，不用重复写错误分支。
     */
    private function storeImage(int $uid, string $folder): ?string
    {
        $file = $_FILES['file'] ?? null;
        if (!is_array($file) || ($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) {
            Json::fail(400, '请选择要上传的图片');
            return null;
        }
        if ((int)$file['size'] > self::MAX_BYTES) {
            Json::fail(400, '图片不能超过 2MB');
            return null;
        }
        // 以文件内容判定类型，客户端给的 name/type 完全不可信
        $info = @getimagesize((string)$file['tmp_name']);
        $ext = is_array($info) ? (self::EXTENSIONS[$info[2]] ?? null) : null;
        if ($ext === null) {
            Json::fail(400, '只支持 JPG / PNG / WEBP');
            return null;
        }

        $dir = ROOT_PATH . 'upload' . DIRECTORY_SEPARATOR . $folder;
        if (!is_dir($dir) && !mkdir($dir, 0755, true) && !is_dir($dir)) {
            Json::fail(500, '目录创建失败');
            return null;
        }

        $name = $uid . '_' . time() . $ext;
        if (!move_uploaded_file((string)$file['tmp_name'], $dir . DIRECTORY_SEPARATOR . $name)) {
            Json::fail(500, '保存失败');
            return null;
        }
        return '/upload/' . $folder . '/' . $name;
    }
}
