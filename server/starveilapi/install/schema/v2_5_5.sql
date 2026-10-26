-- v2.5.5：直播频道分组。
--
-- 背景：后台支持导入 M3U 播放列表，公开源的频道本来就是按 `group-title`
-- 分好的（央视 / 卫视频道 / 港澳台 / 付费频道…），原来只把名称和地址入库，
-- 几百个台在客户端拉成一条长列表，既翻不动也没法按类找。
--
-- `gname` 存的就是 `group-title` 原文（去空白、限长）；没写分组的源留空串，
-- 客户端把空串归到「未分组」。不做单独的分组表：分组只是频道的属性，
-- 而且同一份源重新导入时分组名会整体变化，独立表反而要额外维护同步。

ALTER TABLE `__PREFIX__cuican_zhibo`
  ADD COLUMN `gname` varchar(64) NOT NULL DEFAULT '' AFTER `img`;

ALTER TABLE `__PREFIX__cuican_zhibo`
  ADD KEY `idx_gname` (`gname`);
