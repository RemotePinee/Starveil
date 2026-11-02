<?php
/**
 * 请求参数读取。
 *
 * 与 `input()` 的区别：不返回整个请求数组，只提供按字段读取的方法，
 * 调用方必须显式写字段名——这是防"整表 allowField(true) 更新"的第一道闸。
 */

declare(strict_types=1);

namespace Starveil\Api\Http;

final class Input
{
    public function __construct(
        private array $query,
        private array $body,
    ) {
    }

    public static function capture(): self
    {
        $raw = file_get_contents('php://input');
        $json = [];
        if (is_string($raw) && $raw !== '' && str_contains((string)($_SERVER['CONTENT_TYPE'] ?? ''), 'json')) {
            $decoded = json_decode($raw, true);
            $json = is_array($decoded) ? $decoded : [];
        }
        return new self($_GET, array_merge($_POST, $json));
    }

    public function str(string $key, string $default = ''): string
    {
        $value = $this->raw($key);
        if (is_array($value)) {
            return $default;
        }
        return trim((string)$value) !== '' ? trim((string)$value) : $default;
    }

    public function int(string $key, int $default = 0): int
    {
        $value = $this->raw($key);
        return is_scalar($value) ? (int)$value : $default;
    }

    public function bool(string $key): bool
    {
        return in_array((string)$this->raw($key), ['1', 'true', 'on', 'yes'], true);
    }

    /** 逗号分隔的整数列表，用于批量删除、`type_ids` 这类入参。 */
    public function intList(string $key): array
    {
        $value = $this->raw($key);
        // 数组形式（`?type_ids[]=1`）也收：直接 `(string)` 转换会抛 Array to string 警告，
        // 在开了 display_errors 的站点上会把 JSON 响应弄坏。
        $raw = is_array($value) ? implode(',', array_filter($value, 'is_scalar')) : (string)$value;
        if ($raw === '') {
            return [];
        }
        $ids = array_map('intval', explode(',', $raw));
        return array_values(array_filter($ids, static fn (int $id): bool => $id > 0));
    }

    private function raw(string $key): mixed
    {
        return $this->body[$key] ?? $this->query[$key] ?? null;
    }
}
