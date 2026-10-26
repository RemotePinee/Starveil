-- v2.5.0：提现申请补「收款码」。
--
-- 为什么加：原先只有 account + realname 两段文本，微信/支付宝的收款码是图，
-- 运营在后台要照着账号去加好友再要图，审核链路多一跳；客户端这版直接上传
-- 收款码图片，路径随申请单一起落库，后台点开即见。
--
-- 为什么放 realname 后面：只是让 `SELECT *` 的列顺序和后台「收款信息」列
-- 的展示顺序一致，读写都用列名，位置本身不影响任何 SQL。
--
-- 为什么 DEFAULT '' 且 NOT NULL：请求里可能没有 qrcode，服务端写入空串，
-- 客户端行为与展示完全不变；后台只有非空时才渲染缩略图。
-- 同理，这一列不让任何已有列改类型/改默认值，回滚一版插件也只会多一列空值。
--
-- 幂等：MySQL 8 的 ADD COLUMN 不支持 IF NOT EXISTS。重复执行（--force / repair）
-- 会报 1060 Duplicate column，Installer::isIgnorable() 已把 1060 与
-- "Duplicate column" 视为可忽略，迁移不会因此失败。

ALTER TABLE `__PREFIX__cuican_gold_withdraw`
  ADD COLUMN `qrcode` varchar(255) NOT NULL DEFAULT '' AFTER `realname`;
