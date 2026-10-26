<?php
/**
 * macCMS 上传结果的地址归一化与图片处理。
 *
 * 为什么不用 `mac_url_img()`：它依赖 `MAC_PATH` 常量，而这个常量由 macCMS 的
 * `index.php` 流程注入；插件的独立入口（`icciu_api.php` / `addons/.../admin.php`）
 * 没有它，PHP 8 下直接拼未定义常量会抛 Error。这里按 macCMS 的上传配置自己拼：
 *
 * - 完整 `http(s)://` 或协议相对 `//`：原样返回（云存储/远程/CDN 都是这种）；
 * - `upload.mode = remote`：拼 `upload.remoteurl`（与 macCMS 的 mac_url_img 同口径）；
 * - 其余：当前请求域名 + `site.install_dir` + 相对路径。
 *
 * 上传仍走 macCMS 的 `Upload` 模型，格式校验、水印、远程存储都由 CMS 负责；
 * 这个类只补两件 CMS 没管的事：正确的调用方式，以及落盘后的瘦身。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

final class Media
{
    /** 图片长边上限：运营图最大用到 480×270 的 3 倍屏，1600 已经有余量。 */
    private const MAX_EDGE = 1600;

    /** JPEG 重编码质量。低于 80 开始能看出糊，85 在视觉无损与体积之间。 */
    private const JPEG_QUALITY = 85;

    /** 小于这个体积且没超长边的图完全不碰——二次压缩只会掉画质。 */
    private const SKIP_BYTES = 300 * 1024;

    /** 直投激励视频的体积上限：9:16、≤15 秒的 H.264 片源 20MB 已经很宽松。 */
    private const VIDEO_MAX_BYTES = 20 * 1024 * 1024;

    /**
     * 视频只收这两种容器：扩展名 → finfo 认的 MIME。
     *
     * `application/mp4` 也在白名单里：libmagic 对部分 MP4 会报这个，
     * 只认 `video/mp4` 会让运营传一个正常片源却一直失败。
     */
    private const VIDEO_TYPES = [
        'mp4' => ['video/mp4', 'application/mp4'],
        'webm' => ['video/webm'],
    ];

    /** 相对路径 → 客户端/浏览器可直接加载的绝对地址。 */
    public static function url(string $path): string
    {
        $path = trim($path);
        if ($path === '') {
            return '';
        }
        if (preg_match('#^(https?:)?//#i', $path) === 1) {
            return $path;
        }

        $upload = (array)config('maccms.upload');
        $mode = strtolower((string)($upload['mode'] ?? 'local'));
        if ($mode === 'remote' && !empty($upload['remoteurl'])) {
            return rtrim((string)$upload['remoteurl'], '/') . '/' . ltrim($path, '/');
        }

        $install = trim((string)config('maccms.site.install_dir'), '/');
        $prefix = $install === '' ? '/' : '/' . $install . '/';
        try {
            $domain = request()->domain();
        } catch (\Throwable $e) {
            // CLI（自检/定时任务）没有请求上下文，返回站内相对路径即可
            return $prefix . ltrim($path, '/');
        }
        return $domain . $prefix . ltrim($path, '/');
    }

    /**
     * 走 macCMS 的上传模型，返回库内存储值（相对 `upload/...` 或远程完整 URL）。
     *
     * 两个点必须按源码约定处理，少一个后台保存就是 500：
     *
     * 1. `Upload::upload()` 里是无条件读 `$param['imgdata']` 的
     *    （`application/common/model/Upload.php`，v2026.1000.4056 同样如此）。
     *    不传这个键就是 "Undefined array key"，TP5 的 `Error::appError` 会把警告
     *    抛成 ErrorException——表现出来正是"图片传得上去，一保存就网络错误"。
     * 2. `model('Upload')` 在本插件入口（addons 下的独立 admin.php，BIND_MODULE=admin）
     *    会解析成 `app\admin\model\Upload`。这个类不一定存在，所以按能不能找到来取。
     *
     * `upload_return()` 的结构还取决于 `ENTRANCE`：index 入口回 `file`（拼了随机查询串），
     * 其它入口回 `data.file`，这里两种都认，并剥掉查询串只留路径。
     */
    public static function upload(string $input, string $flag): ?string
    {
        if (empty($_FILES[$input]['name'])) {
            return null;
        }
        $model = class_exists('\app\admin\model\Upload')
            ? '\app\admin\model\Upload'
            : '\app\common\model\Upload';
        // 有三件 macCMS 源码隐含的前提，缺一件后台保存就是 500：
        //
        // 1. 进程工作目录必须是站点根：`Upload::upload()` 用相对路径
        //    `upload/{flag}/...` 去 fopen，而 php-fpm 的 cwd 是「当前脚本所在目录」。
        //    官方后台入口在站点根所以没事，本插件入口在 addons/starveilapi 下，
        //    相对路径会落到插件目录里，fopen 拿到 false，PHP 8 下 fread(false) 直接 TypeError。
        // 2. `Request::module()` 不能是空：`Upload::upload()` 内部会 `model('Annex')`，
        //    模块为空时 TP5 把它解析成 `app\model\Annex`（不存在），
        //    正确结果是 `app\common\model\Annex`。本入口不经过 macCMS 的模块分发，
        //    所以要显式把模块补成 admin（与 BIND_MODULE 一致）。
        // 3. `$param['imgdata']` 必须传（源码无条件读它，见方法注释）。
        try {
            \think\Request::instance()->module('admin');
        } catch (\Throwable $e) {
            // 拿不到 Request 就让下面的调用自己报错，不在这里吞
        }
        $cwd = getcwd();
        if ($cwd !== false) {
            @chdir(ROOT_PATH);
        }
        try {
            $result = (new $model())->upload([
                'flag' => $flag,
                'input' => $input,
                'imgdata' => '',
            ]);
        } finally {
            if ($cwd !== false) {
                @chdir($cwd);
            }
        }

        if (!is_array($result) || (int)($result['code'] ?? 0) !== 1) {
            return null;
        }
        $file = (string)($result['data']['file'] ?? $result['file'] ?? '');
        $file = (string)preg_replace('/\?.*$/', '', $file);
        if ($file === '') {
            return null;
        }
        self::shrink($file);
        return $file;
    }

    /**
     * 落盘后的瘦身：只缩不放，超长边或超大体积才处理。
     *
     * 三条约束保证"不糊"：
     * 1. 小于 [SKIP_BYTES] 且没超长边的图**完全不碰**——二次编码只会掉画质；
     * 2. 只等比缩小，从不放大，也用 `imagecopyresampled` 做高质量重采样；
     * 3. JPEG 质量固定 85，PNG 保留透明通道与压缩级别 6。
     *
     * 远程存储（七牛/又拍/OSS）返回的是完整 URL，本地没有文件可改，直接跳过：
     * 那类站点本来就有图片处理参数，不在这里抢活。
     */
    public static function shrink(string $path): void
    {
        if (preg_match('#^(https?:)?//#i', $path) === 1) {
            return;
        }
        if (!function_exists('imagecreatefromjpeg')) {
            return;
        }
        $file = ROOT_PATH . ltrim($path, '/');
        if (!is_file($file)) {
            return;
        }
        $info = @getimagesize($file);
        if (!is_array($info)) {
            return;
        }
        [$width, $height] = $info;
        if ($width <= 0 || $height <= 0) {
            return;
        }
        $longEdge = max($width, $height);
        $oversize = $longEdge > self::MAX_EDGE;
        $overweight = (int)filesize($file) > self::SKIP_BYTES;
        if (!$oversize && !$overweight) {
            return;
        }

        // 用 if/else 而不是 `match`：分支只有四种，可读性差别不大，
        // 而这段只在图片超限时才加载，写法保守一点少一个语法面。
        $type = (int)$info[2];
        $source = null;
        if ($type === IMAGETYPE_JPEG) {
            $source = @imagecreatefromjpeg($file);
        } elseif ($type === IMAGETYPE_PNG) {
            $source = @imagecreatefrompng($file);
        } elseif ($type === IMAGETYPE_WEBP && function_exists('imagecreatefromwebp')) {
            $source = @imagecreatefromwebp($file);
        }
        if (!is_object($source) && !is_resource($source)) {
            return;
        }

        $scale = $oversize ? self::MAX_EDGE / $longEdge : 1.0;
        $newWidth = max(1, (int)round($width * $scale));
        $newHeight = max(1, (int)round($height * $scale));
        $target = imagecreatetruecolor($newWidth, $newHeight);
        if (!$target instanceof \GdImage) {
            imagedestroy($source);
            return;
        }
        // PNG/WEBP 的透明通道要显式保留，否则边缘会糊上一圈黑
        if ($type !== IMAGETYPE_JPEG) {
            imagealphablending($target, false);
            imagesavealpha($target, true);
            $transparent = imagecolorallocatealpha($target, 0, 0, 0, 127);
            imagefilledrectangle($target, 0, 0, $newWidth, $newHeight, $transparent);
        }
        imagecopyresampled($target, $source, 0, 0, 0, 0, $newWidth, $newHeight, $width, $height);

        // 先写临时文件再原子替换：中途失败不会留下半个文件
        $tmp = $file . '.tmp';
        $saved = match ($type) {
            IMAGETYPE_JPEG => imagejpeg($target, $tmp, self::JPEG_QUALITY),
            IMAGETYPE_PNG => imagepng($target, $tmp, 6),
            IMAGETYPE_WEBP => function_exists('imagewebp') && imagewebp($target, $tmp, self::JPEG_QUALITY),
            default => false,
        };
        imagedestroy($source);
        imagedestroy($target);
        if ($saved && is_file($tmp) && filesize($tmp) > 0) {
            rename($tmp, $file);
            return;
        }
        if (is_file($tmp)) {
            unlink($tmp);
        }
    }

    /**
     * 直投激励视频上传：**不能**复用 [upload]，因为那条路按图片规则校验，视频必失败。
     *
     * 1. macCMS 的 `Upload` 模型先按扩展名白名单过一遍，再用 `getimagesize` 判真实类型，
     *    之后还有水印/缩略图；mp4 到第二步就被判成"不是图片"。
     * 2. 即使强行放行，落盘后 [shrink] 会用 GD 去 `imagecreatefrom*` 一个视频文件，
     *    轻则返回 null 把文件留在半处理状态，重则重编码出一张垃圾图覆盖原文件。
     *
     * 所以这里自己落盘，四道闸：
     * - 体积 ≤20MB（[VIDEO_MAX_BYTES]）；
     * - 扩展名只认 mp4/webm，且客户端给的文件名只取扩展名，出现 `..` 或路径分隔符直接拒；
     * - 真实 MIME 用 finfo 判，必须落在 [VIDEO_TYPES] 里——客户端给的 name/type 完全不可信；
     * - 目标路径固定是 `upload/{flag}/`，落盘后再用 realpath 确认没跑出这个目录
     *   （防符号链接/大小写差异把它带到 web 根之外）。
     *
     * 任何一步不过都返回 null：调用方保持旧值不动，运营重试即可，与图片上传同一口径。
     * 返回的是站内相对路径（`/upload/ad/xxx.mp4`），[url] 会再拼成绝对地址。
     */
    public static function uploadVideo(string $input, string $flag): ?string
    {
        $file = $_FILES[$input] ?? null;
        if (!is_array($file) || (int)($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) {
            return null;
        }
        if ((int)($file['size'] ?? 0) > self::VIDEO_MAX_BYTES) {
            return null;
        }

        $name = (string)($file['name'] ?? '');
        if (str_contains($name, '..') || strpbrk($name, "/\\") !== false) {
            return null;
        }
        $ext = strtolower((string)pathinfo($name, PATHINFO_EXTENSION));
        if (!isset(self::VIDEO_TYPES[$ext])) {
            return null;
        }

        // tmp_name 是 PHP 给的，但仍要确认它确实是本次请求的上传文件：
        // 否则一个伪造的 $_FILES 就能让这里搬走服务器上的任意文件
        $tmp = (string)($file['tmp_name'] ?? '');
        if ($tmp === '' || !is_uploaded_file($tmp)) {
            return null;
        }
        $mime = '';
        if (class_exists('\finfo')) {
            $mime = (string)(new \finfo(FILEINFO_MIME_TYPE))->file($tmp);
        } else {
            // 没开 fileinfo 的机器退回 mime_content_type（同属 fileinfo 扩展，但老版本也能用）
            $mime = (string)@mime_content_type($tmp);
        }
        if (!in_array($mime, self::VIDEO_TYPES[$ext], true)) {
            return null;
        }

        $dir = rtrim(ROOT_PATH . 'upload' . DIRECTORY_SEPARATOR . $flag, DIRECTORY_SEPARATOR);
        if (!is_dir($dir) && !mkdir($dir, 0755, true) && !is_dir($dir)) {
            return null;
        }
        $realDir = realpath($dir);
        if ($realDir === false) {
            return null;
        }
        // 文件名完全由服务端生成，客户端那份只贡献了扩展名
        $target = $dir . DIRECTORY_SEPARATOR . 'reward_' . date('YmdHis')
            . '_' . bin2hex(random_bytes(4)) . '.' . $ext;
        if (!move_uploaded_file($tmp, $target)) {
            return null;
        }
        $real = realpath($target);
        if ($real === false || !str_starts_with($real, $realDir . DIRECTORY_SEPARATOR)) {
            @unlink($target);
            return null;
        }
        return '/upload/' . $flag . '/' . basename($target);
    }
}
