-- 下线「游戏位」与「播放器广告」两张表。
--
-- 为什么删：这两块客户端（Android）从来没有实现过——
--   * 游戏位：`v1.youxi/index` 客户端只有常量，零调用；
--   * 播放器广告：`startup.player_ad` 零引用，暂停贴片已经由「广告位」的暂停位接管。
-- 后台入口、服务端接口、下发字段都已一并摘掉，留着空表只会误导后来的人。
--
-- 按 AGENTS.md 的一般规则不删表（怕误伤数据），这次是产品明确要求，
-- 且两表的数据客户端从未消费过，所以在这里显式 DROP。
-- 表可能不存在（老站点没跑过 v2_0_0），统一用 IF EXISTS。

DROP TABLE IF EXISTS `__PREFIX__cuican_youxi`;

DROP TABLE IF EXISTS `__PREFIX__cuican_player_ad`;
