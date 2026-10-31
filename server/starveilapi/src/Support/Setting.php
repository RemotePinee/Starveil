<?php
/**
 * 插件设置读写。
 *
 * 所有可运营项（皮肤、试看时长、套餐、支付渠道、CORS 白名单）都在 `cuican_setting` 里，
 * 不散落在代码常量里；历史插件把这些写死在 PHP 与 SQL 种子中，改一次要改文件。
 */

declare(strict_types=1);

namespace Starveil\Api\Support;

use Starveil\Api\Installer;
use think\Db;

final class Setting
{
    /** @var array<string,string>|null */
    private static ?array $cache = null;

    public static function get(string $key, string $default = ''): string
    {
        $all = self::all();
        return $all[$key] ?? $default;
    }

    public static function int(string $key, int $default = 0): int
    {
        $value = self::get($key);
        return $value === '' ? $default : (int)$value;
    }

    /** @return array<int,mixed> */
    public static function json(string $key, array $default = []): array
    {
        $raw = self::get($key);
        if ($raw === '') {
            return $default;
        }
        $decoded = json_decode($raw, true);
        return is_array($decoded) ? $decoded : $default;
    }

    /**
     * 解析站配置。允许两种写法：
     * - 只填一个解析接口地址（运营最常见）：当成 parse2；
     * - 完整 JSON：`{"parse2":..,"features":..,"issethead":..,"headers":..}`。
     *
     * 这样后台可以把"解析接口"做成一个普通输入框，而不是逼运营写 JSON。
     *
     * @return array{parse2:string,features:string,issethead:string,headers:string}
     */
    public static function playerInfo(): array
    {
        $raw = trim(self::get('player_info'));
        // 新增字段必须在这里一并返回：读回时漏掉，后台保存后刷新就是空白，
        // 看起来像「保存失败」，实际是写进去了但没显示出来。
        $empty = [
            'parse2' => '',
            'features' => '',
            'issethead' => '',
            'headers' => '',
            'hongguo' => '0',
            'hongguo_match' => '',
        ];
        if ($raw === '') {
            return $empty;
        }
        if ($raw[0] === '{' || $raw[0] === '[') {
            $decoded = json_decode($raw, true);
            if (is_array($decoded)) {
                return [
                    'parse2' => (string)($decoded['parse2'] ?? ''),
                    'features' => (string)($decoded['features'] ?? ''),
                    'issethead' => (string)($decoded['issethead'] ?? ''),
                    'headers' => (string)($decoded['headers'] ?? ''),
                    'hongguo' => (string)($decoded['hongguo'] ?? '0'),
                    'hongguo_match' => (string)($decoded['hongguo_match'] ?? ''),
                ];
            }
        }
        return ['parse2' => $raw] + $empty;
    }

    public static function set(string $key, string $value): void
    {
        $table = Installer::prefix() . 'cuican_setting';
        $exists = Db::table($table)->where('k', $key)->find();
        if ($exists) {
            Db::table($table)->where('k', $key)->update(['v' => $value]);
        } else {
            Db::table($table)->insert(['k' => $key, 'v' => $value]);
        }
        self::$cache = null;
    }

    /** @return array<string,string> */
    private static function all(): array
    {
        if (self::$cache !== null) {
            return self::$cache;
        }
        $rows = Db::table(Installer::prefix() . 'cuican_setting')->select();
        $out = [];
        foreach ($rows as $row) {
            $out[(string)$row['k']] = (string)($row['v'] ?? '');
        }
        self::$cache = $out;
        return $out;
    }
}
