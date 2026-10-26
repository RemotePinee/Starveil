-- v2.5.1：首页快捷入口改版。
--
-- 旧卡片是「内置图标 + 标题」：图标是客户端枚举（rank/topic/live/vip/user），
-- 运营改不了图，视觉也和海报墙不是一套。改成「背景图 + 标题 + 副标题」：
-- 图标列保留但不读不写，老数据不回滚、不删列。
--
-- bg 存 macCMS 上传返回值（本地 `upload/links/...`，远程是完整 URL），
-- 与 mac_vod.vod_pic 的口径一致；下发时由 Repo\Links 拼成绝对地址。
--
-- 幂等：MySQL 8 的 ADD COLUMN 不支持 IF NOT EXISTS，重复执行（repair）会报
-- 1060 Duplicate column，Installer::isIgnorable() 已把 1060 与
-- "Duplicate column" 视为可忽略，迁移不会因此失败。

ALTER TABLE `__PREFIX__cuican_link`
  ADD COLUMN `subtitle` varchar(50) NOT NULL DEFAULT '' AFTER `title`,
  ADD COLUMN `bg` varchar(255) NOT NULL DEFAULT '' AFTER `subtitle`;
