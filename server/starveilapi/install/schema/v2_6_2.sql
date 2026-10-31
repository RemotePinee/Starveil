-- v2.6.2：付费墙收敛成唯一开关 paywall_enabled。
--
-- 背景：原来是 trysee_status(0/1/2) + trysee_minutes + trysee_episodes 三件套，
-- 运营得同时理解「三档状态」「试看时长」「试看集数」，而分钟数在没有 CDN 时根本不会生效。
-- 现在只剩 paywall_enabled：
--   关(0) = 全部免费，任何用户都拿到完整播放地址；
--   开(1) = 未解锁用户拿不到地址，解锁方式由会员 / 广告 / 积分三处**已有**配置分别决定
--           （三种同时生效，配了哪种客户端就显示哪种按钮）。
--
-- 迁移只做一件事：把老站点 trysee_status > 0 折算成 paywall_enabled = 1。
-- trysee_status / trysee_minutes / trysee_episodes 的旧值**保留在表里不删**：
-- 代码已不再读它们，留着是为了回滚——把旧版本插件目录放回来即可继续按老口径跑。
--
-- 幂等：cuican_setting 的主键是 k，INSERT IGNORE 重复执行只会命中"已存在"而不覆盖，
-- 所以运营在后台手动改过的值不会被这条迁移冲掉（--force / repair 重跑同样安全）。

-- 1) 有 trysee_status 的站点：> 0 折算成开启，0 保持全部免费
INSERT IGNORE INTO `__PREFIX__cuican_setting` (`k`, `v`)
SELECT 'paywall_enabled', IF(CAST(`v` AS UNSIGNED) > 0, '1', '0')
  FROM `__PREFIX__cuican_setting`
 WHERE `k` = 'trysee_status'
 LIMIT 1;

-- 2) 没有 trysee_status 这一行的库（全新库、被手工改过的库）：补默认值 0，
--    语义与老版本"未配置 = 免费"一致，不会因为缺键把整站锁死
INSERT IGNORE INTO `__PREFIX__cuican_setting` (`k`, `v`)
VALUES ('paywall_enabled', '0');
