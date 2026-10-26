-- v2.3.0：专题选片支持人工排序（插件后台「专题」页的上移/下移）。
-- 迁移器按文件名排序执行，已应用的版本记录在 cuican_schema_version。
-- 只加列/索引，不删不改；重复执行时由 Installer::isIgnorable 吞掉 1060。

ALTER TABLE `__PREFIX__cuican_topic_vod`
  ADD COLUMN `sort` int(11) NOT NULL DEFAULT 0 AFTER `vod_id`,
  ADD KEY `idx_topic_sort` (`topic_id`,`sort`);
