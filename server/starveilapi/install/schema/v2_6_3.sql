-- v2.6.3：CDN 播放路径接通，新增 cdn_trysee_minutes。
--
-- 语义只有三条，别再加分支：
--   paywall_enabled = 0                → 全部原始地址（免费）；
--   paywall_enabled = 1, cdn_enabled=0 → 一个地址都不给，只能靠会员 / 看广告 / 积分兑换解锁；
--   paywall_enabled = 1, cdn_enabled=1 → 给签名 CDN 地址，exper = cdn_trysee_minutes × 60
--                                        （0 = 不截断，即等价于不给地址）。
--
-- 试看**依赖 CDN**：CDN 关闭时没有试看，未解锁用户完全拿不到地址。
-- 已解锁用户（会员 / 已购这集 / 看完广告）任何时候都给完整地址；CDN 开时也走 CDN，exper = 0。
--
-- 幂等：cuican_setting 主键是 k，INSERT IGNORE 重复执行只会命中"已存在"，
-- 不会覆盖运营在后台填过的值（--force / repair 重跑同样安全）。

INSERT IGNORE INTO `__PREFIX__cuican_setting` (`k`, `v`) VALUES ('cdn_trysee_minutes', '0');
