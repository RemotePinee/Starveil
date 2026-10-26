-- v2.2.0：首页内容位插件化 + 短剧解锁。
-- 迁移器按文件名排序执行，已应用的版本记录在 cuican_schema_version。
-- 约束：只 CREATE IF NOT EXISTS、不写 DROP、表名统一 __PREFIX__cuican_ 前缀。

-- 轮播（后台「首页装修 → 轮播」）
CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_banner` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `title` varchar(100) NOT NULL DEFAULT '',
  `subtitle` varchar(200) NOT NULL DEFAULT '',
  `pic` varchar(255) NOT NULL DEFAULT '',
  `vod_id` int(10) unsigned NOT NULL DEFAULT 0,
  `url` varchar(255) NOT NULL DEFAULT '',
  `style` varchar(16) NOT NULL DEFAULT '',
  `sort` int(11) NOT NULL DEFAULT 0,
  `status` tinyint(3) unsigned NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_status_sort` (`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 首页专区（卡片组）
CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_block` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `title` varchar(100) NOT NULL DEFAULT '',
  `subtitle` varchar(200) NOT NULL DEFAULT '',
  `layout` varchar(16) NOT NULL DEFAULT 'row',
  `position` varchar(16) NOT NULL DEFAULT 'home',
  `sort` int(11) NOT NULL DEFAULT 0,
  `status` tinyint(3) unsigned NOT NULL DEFAULT 1,
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_pos_status` (`position`,`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_block_vod` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `block_id` int(10) unsigned NOT NULL DEFAULT 0,
  `vod_id` int(10) unsigned NOT NULL DEFAULT 0,
  `sort` int(11) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_block_vod` (`block_id`,`vod_id`),
  KEY `idx_block_sort` (`block_id`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 快捷入口（首页搜索栏下方那排）
CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_link` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `title` varchar(50) NOT NULL DEFAULT '',
  `icon` varchar(32) NOT NULL DEFAULT '',
  `target_type` varchar(16) NOT NULL DEFAULT 'url',
  `target_id` int(10) unsigned NOT NULL DEFAULT 0,
  `url` varchar(255) NOT NULL DEFAULT '',
  `sort` int(11) NOT NULL DEFAULT 0,
  `status` tinyint(3) unsigned NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_status_sort` (`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 短剧广告解锁（SSV 回调写这里，唯一索引防重复回调）
CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_shortplay_unlock` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(10) unsigned NOT NULL DEFAULT 0,
  `vod_id` int(10) unsigned NOT NULL DEFAULT 0,
  `episode_number` int(10) unsigned NOT NULL DEFAULT 0,
  `unlock_type` varchar(16) NOT NULL DEFAULT '',
  `ad_trans_id` varchar(64) NOT NULL DEFAULT '',
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ad_trans` (`ad_trans_id`),
  KEY `idx_user_vod` (`user_id`,`vod_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
