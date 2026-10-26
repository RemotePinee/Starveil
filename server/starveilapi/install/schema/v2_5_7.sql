-- v2.5.7：订单加「已开通」标记，会员开通只认没开通过的单。
--
-- 背景：会员有两条开通路径（支付回调 + 客户端支付完成后的 `v1.user/group`），
-- 两条都无条件延长会员，买 30 天能到账 60 天。现在由 `opened_at` 的条件更新
-- 决定"这单归谁开"：affected=1 的请求才延长，后来的只回「会员已开通」。
--
-- 为什么用 int 时间戳而不是 tinyint 标记：排查线上问题时能直接看出是哪一刻开的，
-- 而多出来的 4 字节对一个订单表无所谓。
--
-- 幂等：MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS，重复执行（--force / repair）
-- 会报 1060 Duplicate column，Installer::isIgnorable() 已把它视为可忽略。

ALTER TABLE `__PREFIX__cuican_order`
  ADD COLUMN `opened_at` int(10) unsigned NOT NULL DEFAULT 0 COMMENT '会员开通时间，0=未开通' AFTER `ulog_data`;
