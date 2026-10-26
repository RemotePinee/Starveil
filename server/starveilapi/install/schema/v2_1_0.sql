-- v2.1.0：补 2.0.0 漏掉的评论表（`v1.comment` 在用）。
-- 迁移器按文件名排序执行，已应用的版本记录在 cuican_schema_version。

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_comment` (
  `comment_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `vod_id` int(10) unsigned NOT NULL DEFAULT 0,
  `user_id` int(10) unsigned NOT NULL DEFAULT 0,
  `content` varchar(300) NOT NULL DEFAULT '',
  `status` tinyint(3) unsigned NOT NULL DEFAULT 1,
  `created_at` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`comment_id`),
  KEY `idx_vod_status` (`vod_id`,`status`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
