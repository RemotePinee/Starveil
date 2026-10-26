-- v2.5.2：首页卡片位按分类配置。
--
-- 背景：热度榜/猜你喜欢原来是全局一份（position=hot/guess 只有一个组），
-- 客户端切到"电影/剧集/动漫"后仍然显示同一批片，等于分类页没有自己的运营位。
--
-- 约定：type_id = 0 表示全局默认（首页及未单独配置的分类都会回退到它）；
-- type_id > 0 对应 mac_type.type_id，只对那个分类生效。
--
-- 不加索引：单表几十行，按 position+type_id+status 全表扫的代价可以忽略，
-- 而重复执行时 ADD KEY 报 1061 Duplicate key name 不在 isIgnorable 白名单里，
-- 会让 repair 失败。只加列，重复执行报 1060 已被忽略。
--
-- 幂等：MySQL 8 的 ADD COLUMN 不支持 IF NOT EXISTS，重复执行（repair）会报
-- 1060 Duplicate column，Installer::isIgnorable() 已把 1060 与
-- "Duplicate column" 视为可忽略，迁移不会因此失败。

ALTER TABLE `__PREFIX__cuican_block`
  ADD COLUMN `type_id` int(10) unsigned NOT NULL DEFAULT 0 AFTER `position`;
