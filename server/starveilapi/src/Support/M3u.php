<?php

declare(strict_types=1);

namespace Starveil\Api\Support;

/**
 * 播放列表解析：M3U / M3U8 / TXT（IPTV 源常见的两种写法）。
 *
 * 网上公开的直播源绝大多数是这两种：`.m3u` 用 `#EXTINF` 行描述频道、下一行是地址；
 * `.txt` 用「分组,#genre#」开一个分组，下面逐行「频道名,地址」。后台「直播」原来是
 * 单条手工录入，这份解析器让它能整份导入。
 *
 * 只做两件事：抓取（限 http/https + 大小上限）与解析。不要被扩展名骗了：
 * 判断格式看内容——同一个解析器两种都认，服务端也不需要知道后缀。
 * 不入库、不判断码流是否可用——播放地址是第三方给的，能不能播由播放器决定。
 */
final class M3u
{
    /**
     * 单份列表最多解析这么多条。
     *
     * 公开源（Guovin / iptv-org）动辄一两千条，上限太低会把真实源截成一小截，
     * 看起来像"这份源没几个台"。几万行的异常文件仍然挡得住。
     */
    private const MAX_ITEMS = 2000;

    /** 抓取上限 2MB：正常直播列表几十 KB，超过基本是异常内容。 */
    private const MAX_BYTES = 2_097_152;

    /**
     * 拉取播放列表文本。
     *
     * @return string 失败返回空串（调用方按"没解析到条目"处理）
     */
    public static function fetch(string $url): string
    {
        $url = trim($url);
        // 只允许 http/https：避免 file:// 之类的本地读取
        if (preg_match('#^https?://#i', $url) !== 1) {
            return '';
        }
        $context = stream_context_create([
            'http' => [
                'timeout' => 15,
                'follow_location' => 1,
                'max_redirects' => 3,
                'user_agent' => 'Starveil/1.0 (+m3u import)',
            ],
            'https' => [
                'timeout' => 15,
                'user_agent' => 'Starveil/1.0 (+m3u import)',
            ],
        ]);
        $text = @file_get_contents($url, false, $context, 0, self::MAX_BYTES);
        return is_string($text) ? $text : '';
    }

    /**
     * 解析出频道列表。M3U 与 TXT 同一套逻辑认，格式看内容不看扩展名。
     *
     * @return list<array{name:string,logo:string,group:string,url:string}>
     */
    public static function parse(string $text): array
    {
        if (trim($text) === '') {
            return [];
        }
        // 中文 IPTV 源常见 GBK / GB2312：直接按 UTF-8 切，台名会变成一串乱码，
        // 写库时还可能撞上 1366 Incorrect string value。先统一转成 UTF-8 再解析。
        if (!mb_check_encoding($text, 'UTF-8')) {
            $converted = @mb_convert_encoding($text, 'UTF-8', 'GB18030');
            if (is_string($converted) && trim($converted) !== '') {
                $text = $converted;
            }
        }
        $lines = preg_split('/\r\n|\n|\r/', $text) ?: [];
        $items = [];
        $pending = null;
        // TXT 源的分组是「一组一行」的：#genre# 之前就是组名，直到下一个 #genre# 为止
        $txtGroup = '';
        foreach ($lines as $line) {
            // 第一行常带 BOM，不去掉的话组名/台名前面会挂一个不可见字符
            $line = trim(ltrim($line, "\xEF\xBB\xBF"));
            if ($line === '' || str_starts_with($line, '#EXTM3U')) {
                continue;
            }
            // TXT：「分组,#genre#」，也见过「分组,#Genre#」
            if (preg_match('/^(.*?),\s*#genre#\s*$/i', $line, $match) === 1) {
                $txtGroup = trim((string)$match[1]);
                continue;
            }
            if (str_starts_with($line, '#EXTINF')) {
                $pending = self::meta($line);
                continue;
            }
            // #EXTGRP / #EXTVLCOPT / #KODIPROP 之类的附加指令，跳过
            if ($line[0] === '#') {
                continue;
            }
            // TXT 的频道行：「频道名,地址」。名字里可以带空格、地址里可能带查询串，
            // 所以只按第一个逗号切；名字为空的行是脏数据，丢掉。
            if ($pending === null && str_contains($line, ',')) {
                [$name, $stream] = explode(',', $line, 2);
                $name = trim($name);
                $stream = self::cleanUrl($stream);
                if ($name !== '' && $stream !== '') {
                    $items[] = ['name' => $name, 'logo' => '', 'group' => $txtGroup, 'url' => $stream];
                }
                if (count($items) >= self::MAX_ITEMS) {
                    break;
                }
                continue;
            }
            // 不带 #EXTINF 的单条 m3u8 也收：有些源就是一行地址
            $items[] = [
                'name' => (string)($pending['name'] ?? ''),
                'logo' => (string)($pending['logo'] ?? ''),
                'url' => self::cleanUrl($line),
            ];
            $pending = null;
            if (count($items) >= self::MAX_ITEMS) {
                break;
            }
        }
        // 只留 https：Android 的 cleartext 策略会把 http 流全部拦掉
        // （ExoPlayer 报 ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED），
        // 导进来也只是列表里一排点了没反应的死台。
        return array_values(array_filter(
            $items,
            static fn (array $item): bool => stripos($item['url'], 'https://') === 0,
        ));
    }

    /**
     * 归一化播放地址：txt 源的地址列常带后缀，原样存进来客户端只会播不出来。
     *
     * - `url$官方源ᴴᴰ`：`$` 后面是这条线路的名字，不是地址；
     * - `url|User-Agent=...`：播放器私有 header 写法，留着整条地址就失效；
     * - `url1#url2#url3`：同一频道的多条线路，取第一条。
     *
     * 这三种写法在公开 txt 源里很常见（例如 8.138.7.223 那份就用了 `$`）。
     */
    private static function cleanUrl(string $url): string
    {
        foreach (['$', '|', '#'] as $separator) {
            $pos = strpos($url, $separator);
            if ($pos !== false) {
                $url = substr($url, 0, $pos);
            }
        }
        return trim($url);
    }

    /**
     * 取 `#EXTINF` 行里的属性与频道名。
     *
     * @return array{name:string,logo:string,group:string}
     */
    private static function meta(string $line): array
    {
        $logo = '';
        $group = '';
        if (preg_match('/tvg-logo="([^"]*)"/i', $line, $match) === 1) {
            $logo = $match[1];
        }
        if (preg_match('/group-title="([^"]*)"/i', $line, $match) === 1) {
            $group = $match[1];
        }
        // 频道名在最后一个逗号之后：#EXTINF:-1 tvg-id="x" group-title="y",CCTV-1
        $name = '';
        $pos = strrpos($line, ',');
        if ($pos !== false) {
            $name = trim(substr($line, $pos + 1));
        }
        return ['name' => $name, 'logo' => $logo, 'group' => $group];
    }
}
