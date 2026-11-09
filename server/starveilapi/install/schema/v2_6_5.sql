-- v2.6.5：消息中心（公告 / 推送）与按用户已读。
--
-- 背景：cuican_message 原来是「一条广播 + 一个全站共享的 status」。
-- 这个模型只有一个人读过就会把状态改掉（谁先打开谁"代表"全站已读），
-- 所以已读必须按用户一人一行单独存到 cuican_message_read，
-- 列表接口按 (msg_id, user_id) 判定 read，**不再拿 status 判未读**。
--
-- status 列保留、新消息仍写 0：回滚到旧版本插件后继续按老口径跑，不做数据迁移。
--
-- 新列全部有默认值：老站点升级上来，历史广播就是「推送 / 纯文字 / 无跳转 / 不强制 / 不置顶」，
-- 客户端拿到的弹窗与升级前一致，不会出现空字段。
--
-- 幂等：MySQL 8 的 ADD COLUMN 不支持 IF NOT EXISTS，重复执行（--force / repair）
-- 会报 1060 Duplicate column，Installer::isIgnorable() 已把 1060 与 "Duplicate column"
-- 视为可忽略；拆成一条列一个语句（而不是一个 ALTER 加十列）是为了让"手工加过一半列"
-- 的库也能逐条补完，而不是整条 ALTER 一起失败。
--
-- 注意 COMMENT 必须写在 AFTER 之前：`ADD COLUMN 列定义 [FIRST|AFTER 列]` 里
-- 位置子句在整个列定义之后，COMMENT 属于列定义——写反了是 1064 语法错误，
-- 而 1064 不在 isIgnorable 白名单里，整次迁移都会失败。
--
-- 不加排序索引：单表几十行，`pinned desc, msg_id desc` 全表扫的代价可以忽略，
-- 而重复执行时 ADD KEY 报的 1061 虽然已在白名单里，少一条语句就少一处出错的可能。

ALTER TABLE `__PREFIX__cuican_message`
  ADD COLUMN `kind` tinyint(3) unsigned NOT NULL DEFAULT 1
  COMMENT '1=推送（运营活动）2=公告（系统通知）' AFTER `content`;

ALTER TABLE `__PREFIX__cuican_message`
  ADD COLUMN `template` varchar(16) NOT NULL DEFAULT 'text'
  COMMENT '弹窗模板：text|image|lottie' AFTER `kind`;

ALTER TABLE `__PREFIX__cuican_message`
  ADD COLUMN `cover` varchar(255) NOT NULL DEFAULT ''
  COMMENT '首图地址，template=image 用' AFTER `template`;

ALTER TABLE `__PREFIX__cuican_message`
  ADD COLUMN `icon` varchar(32) NOT NULL DEFAULT ''
  COMMENT '内置图标名（info/gift/update…），不走上传' AFTER `cover`;

ALTER TABLE `__PREFIX__cuican_message`
  ADD COLUMN `badge` varchar(16) NOT NULL DEFAULT ''
  COMMENT '角标文案（限时/NEW），空串不显示' AFTER `icon`;

ALTER TABLE `__PREFIX__cuican_message`
  ADD COLUMN `cta` varchar(32) NOT NULL DEFAULT ''
  COMMENT '主按钮文案，空串由客户端按 kind 取默认值' AFTER `badge`;

ALTER TABLE `__PREFIX__cuican_message`
  ADD COLUMN `action_type` varchar(16) NOT NULL DEFAULT 'none'
  COMMENT '点击跳转：none|vod|type|url' AFTER `cta`;

ALTER TABLE `__PREFIX__cuican_message`
  ADD COLUMN `action_value` varchar(255) NOT NULL DEFAULT ''
  COMMENT '跳转参数：vod/type 是 id，url 是地址' AFTER `action_type`;

ALTER TABLE `__PREFIX__cuican_message`
  ADD COLUMN `forced` tinyint(1) unsigned NOT NULL DEFAULT 0
  COMMENT '1=必须看完才能关' AFTER `action_value`;

ALTER TABLE `__PREFIX__cuican_message`
  ADD COLUMN `pinned` tinyint(1) unsigned NOT NULL DEFAULT 0
  COMMENT '1=常驻消息列表顶部' AFTER `forced`;

-- 已读：一个用户一条消息一行。
-- 主键 (msg_id, user_id) 同时承担"同一用户重复标记已读只留一行"的职责，
-- 标记已读的写入路径据此可以幂等（详见 Repo/Push::markRead）；
-- 另加 user_id 单列索引：未读条数与"我读过的"都按 user_id 过滤，
-- 主键前缀是 msg_id，单靠主键要走全表扫。
CREATE TABLE IF NOT EXISTS `__PREFIX__cuican_message_read` (
  `msg_id` int(10) unsigned NOT NULL DEFAULT 0,
  `user_id` int(10) unsigned NOT NULL DEFAULT 0,
  `read_at` int(10) unsigned NOT NULL DEFAULT 0 COMMENT '标记已读的时间',
  PRIMARY KEY (`msg_id`,`user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息已读（按用户）';
