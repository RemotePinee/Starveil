-- 星幕 API 初始结构 v2.0.0
--
-- 三条硬规则（与旧插件相反）：
-- 1. 没有 DROP TABLE：全部 CREATE TABLE IF NOT EXISTS，重复执行安全；
-- 2. 表名走 __PREFIX__ 占位，安装时替换成站点前缀；
-- 3. 全部 InnoDB + utf8mb4，索引在建表时给。

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_schema_version` (
  `version` varchar(32) NOT NULL,
  `applied_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_setting` (
  `k` varchar(64) NOT NULL,
  `v` text NULL,
  PRIMARY KEY (`k`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_user_ext` (
  `user_id` int(10) unsigned NOT NULL,
  `gold` int(10) NOT NULL DEFAULT 0,
  `points` int(10) NOT NULL DEFAULT 0,
  `agent_uid` int(10) unsigned NOT NULL DEFAULT 0,
  `agent_level` tinyint(3) unsigned NOT NULL DEFAULT 0,
  `last_sign_date` date NULL,
  `view_seconds` int(10) unsigned NOT NULL DEFAULT 0,
  `updated_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`user_id`),
  KEY `idx_agent` (`agent_uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_order` (
  `order_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `order_code` varchar(40) NOT NULL,
  `user_id` int(10) unsigned NOT NULL,
  `order_price` decimal(10,2) NOT NULL DEFAULT 0.00,
  `order_status` tinyint(3) unsigned NOT NULL DEFAULT 0,
  `order_points` int(10) NOT NULL DEFAULT 0,
  `order_pay_type` varchar(32) NOT NULL DEFAULT '',
  `order_time` int(10) unsigned NOT NULL DEFAULT 0,
  `order_pay_time` int(10) unsigned NOT NULL DEFAULT 0,
  `target_type` varchar(16) NOT NULL DEFAULT 'vip',
  `target_id` int(10) unsigned NOT NULL DEFAULT 0,
  `ulog_data` text NULL,
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `uk_order_code` (`order_code`),
  KEY `idx_user_status` (`user_id`,`order_status`),
  KEY `idx_status_time` (`order_status`,`order_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_pay_channel` (
  `code` varchar(32) NOT NULL,
  `name` varchar(64) NOT NULL DEFAULT '',
  `status` tinyint(3) unsigned NOT NULL DEFAULT 0,
  `sort` int(10) NOT NULL DEFAULT 0,
  `config` text NULL,
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_card` (
  `card_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `card_no` varchar(64) NOT NULL,
  `card_pwd` varchar(128) NOT NULL,
  `card_type` varchar(16) NOT NULL DEFAULT 'vip',
  `card_value` int(10) NOT NULL DEFAULT 0,
  `card_status` tinyint(3) unsigned NOT NULL DEFAULT 0,
  `used_by` int(10) unsigned NOT NULL DEFAULT 0,
  `used_time` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`card_id`),
  UNIQUE KEY `uk_card_no` (`card_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_ulog` (
  `ulog_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(10) unsigned NOT NULL,
  `vod_id` int(10) unsigned NOT NULL,
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`ulog_id`),
  UNIQUE KEY `uk_user_vod` (`user_id`,`vod_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_vlog` (
  `vlog_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(10) unsigned NOT NULL,
  `vod_id` int(10) unsigned NOT NULL,
  `source_index` int(10) unsigned NOT NULL DEFAULT 0,
  `episode_index` int(10) unsigned NOT NULL DEFAULT 0,
  `progress_sec` int(10) unsigned NOT NULL DEFAULT 0,
  `updated_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`vlog_id`),
  UNIQUE KEY `uk_user_vod` (`user_id`,`vod_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_danmu` (
  `danmu_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `vod_id` int(10) unsigned NOT NULL,
  `user_id` int(10) unsigned NOT NULL DEFAULT 0,
  `content` varchar(255) NOT NULL DEFAULT '',
  `at_time` int(10) unsigned NOT NULL DEFAULT 0,
  `color` varchar(16) NOT NULL DEFAULT '',
  `status` tinyint(3) unsigned NOT NULL DEFAULT 1,
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`danmu_id`),
  KEY `idx_vod_time` (`vod_id`,`at_time`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_message` (
  `msg_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(10) unsigned NOT NULL DEFAULT 0,
  `title` varchar(128) NOT NULL DEFAULT '',
  `content` text NULL,
  `status` tinyint(3) unsigned NOT NULL DEFAULT 0,
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`msg_id`),
  KEY `idx_user_status` (`user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_sign` (
  `sign_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(10) unsigned NOT NULL,
  `sign_date` date NOT NULL,
  `points` int(10) NOT NULL DEFAULT 0,
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`sign_id`),
  UNIQUE KEY `uk_user_date` (`user_id`,`sign_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_view30m` (
  `user_id` int(10) unsigned NOT NULL,
  `vod_id` int(10) unsigned NOT NULL DEFAULT 0,
  `ymd` char(8) NOT NULL DEFAULT '',
  `seconds` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`user_id`,`vod_id`,`ymd`),
  KEY `idx_ymd` (`ymd`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_gold_withdraw` (
  `w_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(10) unsigned NOT NULL,
  `amount` int(10) unsigned NOT NULL DEFAULT 0,
  `channel` tinyint(3) unsigned NOT NULL DEFAULT 1,
  `account` varchar(128) NOT NULL DEFAULT '',
  `realname` varchar(64) NOT NULL DEFAULT '',
  `status` tinyint(3) unsigned NOT NULL DEFAULT 0,
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  `handled_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`w_id`),
  KEY `idx_user_status` (`user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_topic` (
  `topic_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL DEFAULT '',
  `pic` varchar(255) NOT NULL DEFAULT '',
  `blurb` varchar(255) NOT NULL DEFAULT '',
  `content` text NULL,
  `status` tinyint(3) unsigned NOT NULL DEFAULT 1,
  `sort` int(10) NOT NULL DEFAULT 0,
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`topic_id`),
  KEY `idx_status_sort` (`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_topic_vod` (
  `topic_id` int(10) unsigned NOT NULL,
  `vod_id` int(10) unsigned NOT NULL,
  PRIMARY KEY (`topic_id`,`vod_id`),
  KEY `idx_vod` (`vod_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_zhibo` (
  `zhibo_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL DEFAULT '',
  `img` varchar(255) NOT NULL DEFAULT '',
  `url` varchar(512) NOT NULL DEFAULT '',
  `status` tinyint(3) unsigned NOT NULL DEFAULT 1,
  `sort` int(10) NOT NULL DEFAULT 0,
  PRIMARY KEY (`zhibo_id`),
  KEY `idx_status_sort` (`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_youxi` (
  `game_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL DEFAULT '',
  `img` varchar(255) NOT NULL DEFAULT '',
  `url` varchar(512) NOT NULL DEFAULT '',
  `status` tinyint(3) unsigned NOT NULL DEFAULT 1,
  `sort` int(10) NOT NULL DEFAULT 0,
  PRIMARY KEY (`game_id`),
  KEY `idx_status_sort` (`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_gbook` (
  `gbook_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(10) unsigned NOT NULL DEFAULT 0,
  `content` varchar(500) NOT NULL DEFAULT '',
  `status` tinyint(3) unsigned NOT NULL DEFAULT 1,
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`gbook_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_app_version` (
  `app_version_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `os` varchar(8) NOT NULL DEFAULT 'android',
  `version` varchar(32) NOT NULL DEFAULT '',
  `summary` text NULL,
  `url` varchar(512) NOT NULL DEFAULT '',
  `is_required` tinyint(3) unsigned NOT NULL DEFAULT 0,
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`app_version_id`),
  KEY `idx_os_version` (`os`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_sms_code` (
  `phone` varchar(20) NOT NULL,
  `code` varchar(8) NOT NULL,
  `purpose` varchar(16) NOT NULL DEFAULT '',
  `expire_at` int(10) unsigned NOT NULL DEFAULT 0,
  `used` tinyint(3) unsigned NOT NULL DEFAULT 0,
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`phone`,`purpose`,`created_at`),
  KEY `idx_phone_purpose` (`phone`,`purpose`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
