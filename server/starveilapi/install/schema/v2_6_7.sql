-- 轮播按分类：0 = 全部（首页）。
--
-- 之前 cuican_banner 没有分类维度，切到动漫/短剧看到的还是同一组轮播。
-- 加一列之后 Repo/Banner::list($typeId) 先按分类查，没配再回退 0。
ALTER TABLE `__PREFIX__cuican_banner` ADD COLUMN `type_id` INT NOT NULL DEFAULT 0;
