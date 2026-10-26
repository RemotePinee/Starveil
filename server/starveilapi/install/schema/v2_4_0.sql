-- v2.4.0：播放器贴片广告（插件自建表）。
-- 迁移器按文件名排序执行，已应用的版本记录在 cuican_schema_version。
--
-- 为什么不用 macCMS 的 mac_ads：
-- mac_ads 是给模板标签 {maccms:ads} 用的 HTML 片段槽，按"广告位"输出一段静态 HTML，
-- 没有视频贴片地址、没有倒计时长度、没有点击链接，也没有暂停图/暂停点击；
-- 而 App 播放器要的是老契约 `v1.Vodad`（ad_select + html{code,timeout} + video{url,click_url}
-- + pause{img,url} 四段）。两者字段对不上，硬塞进 mac_ads 只能把 JSON 塞进 HTML 字段，
-- 运营在 CMS 后台看不懂，还会污染 PC 站模板的广告位列表，所以单独建表。

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_player_ad` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `title` varchar(100) NOT NULL DEFAULT '',
  `ad_type` varchar(16) NOT NULL DEFAULT 'off',   -- off / html / video
  `html_code` text,
  `html_timeout` int(11) NOT NULL DEFAULT 5,
  `video_url` varchar(500) NOT NULL DEFAULT '',
  `click_url` varchar(500) NOT NULL DEFAULT '',
  `pause_img` varchar(500) NOT NULL DEFAULT '',
  `pause_url` varchar(500) NOT NULL DEFAULT '',
  `status` tinyint(3) unsigned NOT NULL DEFAULT 1,
  `sort` int(11) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
