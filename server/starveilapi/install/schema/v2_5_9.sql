-- v2.5.9：直播分组表的排序规则跟其它表对齐。
--
-- v2.5.6 建这张表时只写了 `DEFAULT CHARSET=utf8mb4`，没写 COLLATE，
-- 于是排序规则跟着服务器默认走（MySQL 5.7 / 部分 8.x 是 utf8mb4_general_ci），
-- 而插件其它表都是 utf8mb4_unicode_ci。
--
-- 出了什么：macCMS 老版本把连接字符集设成 utf8（utf8mb3），拿 utf8mb3 的参数去
-- 比较 utf8mb4_general_ci 的列，部分 MySQL/MariaDB 会直接抛
--   1267 Illegal mix of collations (utf8mb4_general_ci,IMPLICIT)
--        and (utf8_general_ci,COERCIBLE) for operation '='
-- 表现是「导入 M3U 一条都进不去 / 加分组报错」。
--
-- 代码里已把这些比较改成在 PHP 里做（见 LiveGroupPanel::nameExists），
-- 这里再把表本身统一到 utf8mb4_unicode_ci，两边都不再依赖隐式转换。
-- 迁移可重复执行：已经是 unicode_ci 时 CONVERT 是空操作。

ALTER TABLE `__PREFIX__cuican_zhibo_group`
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
