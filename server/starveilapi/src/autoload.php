<?php
/**
 * 极简自动加载：插件自带命名空间，不依赖 composer，也不注册到全局。
 *
 * 约定：`Starveil\Api\Foo\Bar` → `<插件目录>/src/Foo/Bar.php`
 */

declare(strict_types=1);

spl_autoload_register(static function (string $class): void {
    $prefix = 'Starveil\\Api\\';
    if (!str_starts_with($class, $prefix)) {
        return;
    }
    $relative = substr($class, strlen($prefix));
    $file = __DIR__ . '/' . str_replace('\\', '/', $relative) . '.php';
    if (is_file($file)) {
        require $file;
    }
});
