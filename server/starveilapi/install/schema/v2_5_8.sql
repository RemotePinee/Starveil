-- v2.5.8：直投激励视频的服务端会话表。
--
-- 背景：现在「看完广告解锁」只认聚合平台的 SSV 回调（`AdSsvController`），
-- 直投（后台自己配 mp4/webm、客户端自己播）没有第三方能签回执。于是改成服务端
-- 自己发会话、自己计时：签发时记 `start_at`，客户端只上报播放进度，服务端按
-- 「进度 ≤ 真实流逝时间 + 3 秒」判定这条进度是真的，看满 `seconds` 才写解锁记录。
--
-- 为什么单开一张表，不复用 `cuican_shortplay_unlock`：那张表只该有"已经解锁"
-- 这一种行。把"看到第 7 秒"这类过程数据塞进去，`Playback::unlocked()` 的查询、
-- 后台的解锁记录列表都要跟着加 `status` 过滤，一处漏掉就是白送解锁。
--
-- 为什么不存 token 的哈希：token 是 32 位随机十六进制（128 位熵），不可枚举；
-- 而排查线上问题时能直接按 token 搜到是哪一次会话，值钱得多。
--
-- 幂等：CREATE TABLE IF NOT EXISTS，重复执行（--force / repair）只是空跑。

CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_ad_session` (
  `token` varchar(64) NOT NULL DEFAULT '' COMMENT '一次性令牌：32 位十六进制',
  `user_id` int(10) unsigned NOT NULL DEFAULT 0,
  `vod_id` int(10) unsigned NOT NULL DEFAULT 0,
  `episode` int(10) unsigned NOT NULL DEFAULT 0 COMMENT '第几集',
  `seconds` int(10) unsigned NOT NULL DEFAULT 0 COMMENT '看满多少秒算完成，签发时定死',
  `max_position` int(10) unsigned NOT NULL DEFAULT 0 COMMENT '已校验通过的最大进度（秒）',
  `start_at` int(10) unsigned NOT NULL DEFAULT 0 COMMENT '签发时间：进度防伪与有效期都以它为准',
  `updated_at` int(10) unsigned NOT NULL DEFAULT 0,
  `completed_at` int(10) unsigned NOT NULL DEFAULT 0 COMMENT '完成时间，0=未完成',
  `status` tinyint(3) unsigned NOT NULL DEFAULT 0 COMMENT '0 进行中 / 1 已完成',
  PRIMARY KEY (`token`),
  KEY `idx_user_vod_ep` (`user_id`,`vod_id`,`episode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='直投激励视频会话';
