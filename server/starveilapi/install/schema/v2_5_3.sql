-- v2.5.3：设备账号（RDA，Recoverable Device Account）。
--
-- 背景：用 `Base64(ANDROID_ID)` 当密码时设备指纹可读（LoginActivity.kt:224），
-- 设备标识可读、可改、可撞，等于没有密码。这里改成两段式：
--   device_hash   = sha256(客户端随机 token)，随机 32 字节，只存哈希，登录凭据
--   recovery_hash = HMAC-SHA256(SSAID, 服务端盐)，只做“找回索引”，永远不能单独换会话
--
-- `recover_at` 是恢复冷却：同一恢复锚 24 小时内只允许转移一次，
-- 防止有人撞出锚就把账号接管走。
--
-- 幂等：ADD COLUMN / ADD KEY 重复执行分别报 1060 与 1061，
-- Installer::isIgnorable() 已把两者视为可忽略。

ALTER TABLE `__PREFIX__cuican_user_ext`
  ADD COLUMN `device_hash` char(64) NULL DEFAULT NULL AFTER `view_seconds`,
  ADD COLUMN `device_name` varchar(64) NOT NULL DEFAULT '' AFTER `device_hash`,
  ADD COLUMN `recovery_hash` char(64) NULL DEFAULT NULL AFTER `device_name`,
  ADD COLUMN `recover_at` int(10) unsigned NOT NULL DEFAULT 0 AFTER `recovery_hash`;

ALTER TABLE `__PREFIX__cuican_user_ext`
  ADD UNIQUE KEY `idx_device_hash` (`device_hash`),
  ADD KEY `idx_recovery_hash` (`recovery_hash`);
