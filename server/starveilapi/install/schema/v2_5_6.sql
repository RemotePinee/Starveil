-- v2.5.6：直播分组独立成表。
--
-- 之前「有哪些分组」是从频道的 `gname` 字段倒推的（SELECT DISTINCT gname）。
-- 这么做的副作用是无法表达「建了分组、但暂时还没有频道」——运营想先建好
-- 「央视 / 卫视 / 港澳台」再往里导，界面上永远是空的，也没法预置顺序。
--
-- 现在分组有自己的表：可以新增、排序、删除；删除前校验该组下有没有频道。
-- 频道表仍用 `gname` 存名字（不做外键），这样导入 M3U 时可以直接写 group-title，
-- 不需要先建组；两边不一致时以频道数据为准，见 `Live::groups()` 的补齐逻辑。

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_zhibo_group` (
  `group_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL DEFAULT '' COMMENT '分组名',
  `sort` int(11) NOT NULL DEFAULT 0 COMMENT '越小越前',
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`group_id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='直播分组';

-- 把频道里已有的分组名回填进新表。名字唯一，重复执行会被 IGNORE 掉。
INSERT IGNORE INTO `__PREFIX__cuican_zhibo_group` (`name`, `sort`)
SELECT DISTINCT `gname`, 0 FROM `__PREFIX__cuican_zhibo` WHERE `gname` <> '';
