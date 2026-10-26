<?php
/**
 * 写库前的文本净化：把「MySQL 连接/表装不下的字符」先削掉。
 *
 * 起因是一个真实故障：macCMS 老版本的数据库连接字符集是 `utf8`（utf8mb3，只有
 * 三字节），而插件表是 utf8mb4。连接是 mb3 时，导入名单里带 emoji 的频道名会让
 * 整条 INSERT 失败：
 *   1366 Incorrect string value: '\xF0\x9F\x98\xBC...' for column 'name'
 * 一条坏名字毁掉整批导入，而且报错那行还不一定是用户能看懂的地方。
 *
 * 所以对外部来的文本（M3U/TXT 里的台名、分组名、运营手填的名字）统一先过一遍
 * [forDb]：连接是 mb3 时去掉四字节字符（emoji 等），连接是 utf8mb4 时原样保留。
 *
 * 注意这是**兼容**手段，不是安全机制：真正的修法是把站点连接字符集改成 utf8mb4。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

use think\Db;

final class Text
{
    /** 连接字符集是不是 mb3（utf8 / utf8mb3 等三字节字符集）；null 表示还没探测。 */
    private static ?bool $mb3 = null;

    /**
     * 写库前的净化：装得下就原样返回，装不下就去掉四字节字符。
     */
    public static function forDb(string $value): string
    {
        return self::connectionIsMb3() ? self::bmpOnly($value) : $value;
    }

    /**
     * 只保留 BMP（U+0000–U+FFFF），也就是去掉所有四字节 UTF-8 字符。
     *
     * 顺手把非法 UTF-8 归一化：外部文件里混进半个字符时，preg 会直接把整串判成
     * 非法并返回空，那等于把台名吃掉了。
     */
    public static function bmpOnly(string $value): string
    {
        if (!mb_check_encoding($value, 'UTF-8')) {
            $value = mb_convert_encoding($value, 'UTF-8', 'UTF-8');
        }
        return (string)preg_replace('/[\x{10000}-\x{10FFFF}]/u', '', $value);
    }

    /** 连接字符集探测，一次请求只查一次。 */
    private static function connectionIsMb3(): bool
    {
        if (self::$mb3 !== null) {
            return self::$mb3;
        }
        self::$mb3 = false;
        try {
            $rows = Db::query("SHOW VARIABLES LIKE 'character_set_connection'");
            $charset = strtolower((string)($rows[0]['Value'] ?? ''));
            // 拿不到就按三字节处理：宁可少一个 emoji，也不要整批导入失败
            self::$mb3 = $charset === '' || !str_contains($charset, 'utf8mb4');
        } catch (\Throwable $e) {
            self::$mb3 = true;
        }
        return self::$mb3;
    }
}
