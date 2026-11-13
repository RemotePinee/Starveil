-- 观看记录带上片名与封面。
--
-- 背景：短剧与红果源的内容 id 不一定存在于 mac_vod，观看记录那条 INNER JOIN 会把
-- 它们整条丢掉；片源下架（vod_status != 1）同理。把展示用的两个字段在写入时一并
-- 落库，历史列表就永远有东西可显示，不再依赖 mac_vod 还在不在。
ALTER TABLE `__PREFIX__cuican_vlog` ADD COLUMN `vod_name` VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE `__PREFIX__cuican_vlog` ADD COLUMN `vod_pic` VARCHAR(255) NOT NULL DEFAULT '';
