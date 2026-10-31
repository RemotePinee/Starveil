-- v2.6.0：订单落集号，提现单落实扣金币。
--
-- 背景一（单集购买解锁整部）：`buyEpisode` 只写 `target_id = 剧 id`，判定也只看剧，
-- 花一集的钱买下整部。`episode_number` 是下单时的集号快照：新单一律按集判；
-- 0 表示"加列之前的老单，不知道第几集"，只能维持整部放行
-- （它们当初也确实是整部解锁的，严格按集号判会把已付费用户直接挡在外面）。
--
-- 背景二（拒绝提现按当前比例退款）：扣金币发生在申请时（按当时的 `gold_per_yuan`），
-- 提现单却只存 `amount`（元）。运营把比例从 10 调到 100 后批量拒绝历史申请，
-- 每单按 10 倍金币退回，还能再提一次。`gold_cost` 记申请时实扣的金币，
-- 拒绝时按它原额退回，不再乘当前比例。
--
-- 幂等：MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS，重复执行（--force / repair）
-- 会报 1060 Duplicate column，Installer::isIgnorable() 已把它视为可忽略。

ALTER TABLE `__PREFIX__cuican_order`
  ADD COLUMN `episode_number` int(10) unsigned NOT NULL DEFAULT 0 COMMENT '单集购买的第几集，0=未记录（加列前的老单）' AFTER `target_id`;

ALTER TABLE `__PREFIX__cuican_gold_withdraw`
  ADD COLUMN `gold_cost` int(10) unsigned NOT NULL DEFAULT 0 COMMENT '申请时实扣金币，拒绝时按它原额退回' AFTER `amount`;
