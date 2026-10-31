-- v2.6.4：CDN 签名支持两种模式，新增 cdn_sign_type。
--
--   tencent = 腾讯云点播 / CDN 的 Key 防盗链，URL 不带源站信息（默认，行为与 v2.6.3 一致）；
--   self    = 自建媒体服务，URL 里带 src（源站 host）供回源，src 并入签名串。
--
-- 老库升级上来没有这个键，[CdnUrlSigner::fromSettings] 与后台下拉都按 tencent 兜底，
-- 所以这条 INSERT 只是把默认值显式落到表里，不改变任何现有站点的下发地址。
--
-- 幂等：cuican_setting 主键是 k，INSERT IGNORE 重复执行只会命中"已存在"，
-- 不会覆盖运营在后台选过的值（--force / repair 重跑同样安全）。

INSERT IGNORE INTO `__PREFIX__cuican_setting` (`k`, `v`) VALUES ('cdn_sign_type', 'tencent');
