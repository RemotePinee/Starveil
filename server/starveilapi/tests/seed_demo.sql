-- 冒烟测试 / 本地预览用的演示数据（不进生产库）。
-- 用法：mysql -uroot -proot --default-character-set=utf8mb4 maccms < tests/seed_demo.sql

SET SESSION sql_mode = '';

UPDATE mac_group
SET group_points_day = 6, group_points_week = 40,
    group_points_month = 150, group_points_year = 1500
WHERE group_id = 3;

INSERT IGNORE INTO mac_cuican_topic (topic_id, name, pic, blurb, content, status, sort, created_at)
VALUES (1, '嫁金钗', 'https://picsum.photos/seed/topic1/900/500', '2026 年热度最快破万剧集', '<p>古装复仇爽剧合集</p>', 1, 0, UNIX_TIMESTAMP());

INSERT IGNORE INTO mac_cuican_topic_vod (topic_id, vod_id) VALUES (1, 1), (1, 7);

INSERT IGNORE INTO mac_cuican_zhibo (zhibo_id, name, img, url, status, sort)
VALUES (1, '测试直播', 'https://picsum.photos/seed/live1/600/400', 'https://live.example.com/1.m3u8', 1, 0);

INSERT IGNORE INTO mac_cuican_pay_channel (code, name, status, sort, config)
VALUES ('epay', '易支付', 1, 0,
'{"driver":"epay","url":"https://pay.example.com/submit.php","pid":"1000","key":"testkey","type":"alipay"}');

INSERT IGNORE INTO mac_cuican_setting (k, v) VALUES ('withdraw_min_yuan', '20'), ('gold_per_yuan', '10');

-- 广告 SSV 测试密钥：tests/smoke.sh 按固定口径构造签名（生产在后台「运营设置 → 广告」填）
INSERT INTO mac_cuican_setting (k, v) VALUES ('ad_ssv_secret', 'smoke-ssv-secret')
ON DUPLICATE KEY UPDATE v = VALUES(v);

DELETE FROM mac_vod;

INSERT INTO mac_vod
(vod_id, type_id, type_id_1, group_id, vod_name, vod_status, vod_pic, vod_remarks, vod_year,
 vod_area, vod_score, vod_blurb, vod_time, vod_play_from, vod_play_url, vod_content, vod_level,
 vod_class, vod_hits)
VALUES
(1, 1, 0, 0, '忠者无敌', 1, 'https://picsum.photos/seed/vod1/700/900', '全40集', '2024', '大陆', '9.1',
 '剧情 战争 / 川军红军携手抗日', UNIX_TIMESTAMP(), '线路一',
 '第01集$https://cdn.example.com/1.m3u8#第02集$https://cdn.example.com/2.m3u8', '剧情', 1, '1', 9800),
(2, 1, 0, 0, '此处通往繁星', 1, 'https://picsum.photos/seed/vod2/700/900', '更新至10集', '2025', '大陆', '8.9',
 '王灿值夜班见鬼', UNIX_TIMESTAMP() - 100, '线路一',
 '第01集$https://cdn.example.com/1.m3u8', '剧情', 1, '1', 8800),
(3, 2, 0, 0, '无可替代', 1, 'https://picsum.photos/seed/vod3/700/900', '限免13集', '2025', '大陆', '8.7',
 '深夜醉酒暧昧升温', UNIX_TIMESTAMP() - 200, '线路一',
 '第01集$https://cdn.example.com/1.m3u8', '剧情', 1, '1', 7600),
(4, 2, 0, 0, '一笑倾城', 1, 'https://picsum.photos/seed/vod4/700/900', '30集全', '2024', '大陆', '8.5',
 '男神独宠学霸系花', UNIX_TIMESTAMP() - 300, '线路一',
 '第01集$https://cdn.example.com/1.m3u8', '剧情', 1, '1', 6900),
(5, 3, 0, 0, '恶女火烧替身夺回一切', 1, 'https://picsum.photos/seed/vod5/700/900', '更新中', '2026', '大陆', '8.8',
 '古装复仇爽剧', UNIX_TIMESTAMP() - 400, '线路一',
 '第01集$https://cdn.example.com/1.m3u8', '剧情', 1, '1', 6400),
(6, 3, 0, 0, '孤女替嫁保命设局反击', 1, 'https://picsum.photos/seed/vod6/700/900', '更新中', '2026', '大陆', '8.6',
 '替嫁逆袭', UNIX_TIMESTAMP() - 500, '线路一',
 '第01集$https://cdn.example.com/1.m3u8', '剧情', 1, '1', 6000),
(7, 4, 0, 0, '嫁金钗', 1, 'https://picsum.photos/seed/vod7/700/900', '全36集', '2026', '大陆', '9.0',
 '2026 年热度最快破万剧集', UNIX_TIMESTAMP() - 600, '线路一',
 '第01集$https://cdn.example.com/1.m3u8', '剧情', 1, '1', 12000),
(8, 4, 0, 0, '一斩', 1, 'https://picsum.photos/seed/vod8/700/900', '全24集', '2025', '大陆', '8.4',
 '武侠动作', UNIX_TIMESTAMP() - 700, '线路一',
 '第01集$https://cdn.example.com/1.m3u8', '剧情', 1, '1', 5600),
(9, 5, 0, 0, '白夜追凶', 1, 'https://picsum.photos/seed/vod9/700/900', '全32集', '2023', '大陆', '9.2',
 '悬疑刑侦', UNIX_TIMESTAMP() - 800, '线路一',
 '第01集$https://cdn.example.com/1.m3u8', '剧情', 1, '1', 15000),
(10, 5, 0, 0, '漫长的季节', 1, 'https://picsum.photos/seed/vod10/700/900', '全12集', '2023', '大陆', '9.4',
 '生活悬疑', UNIX_TIMESTAMP() - 900, '线路一',
 '第01集$https://cdn.example.com/1.m3u8', '剧情', 1, '1', 14200),
(11, 6, 0, 0, '海边的曼彻斯特', 1, 'https://picsum.photos/seed/vod11/700/900', '正片', '2022', '美国', '8.9',
 '剧情家庭', UNIX_TIMESTAMP() - 1000, '线路一',
 '第01集$https://cdn.example.com/1.m3u8', '剧情', 1, '1', 4300),
(12, 6, 0, 0, '爱乐之城', 1, 'https://picsum.photos/seed/vod12/700/900', '正片', '2021', '美国', '8.8',
 '爱情歌舞', UNIX_TIMESTAMP() - 1100, '线路一',
 '第01集$https://cdn.example.com/1.m3u8', '剧情', 1, '1', 3900);

-- 首页内容位（v2.2.0 的 banner / block / link）。固定 id + INSERT IGNORE，可重复执行。
INSERT IGNORE INTO mac_cuican_banner (id, title, subtitle, pic, vod_id, url, style, sort, status)
VALUES
(1, '嫁金钗', '2026 年热度最快破万剧集', 'https://picsum.photos/seed/banner1/1200/600', 7, '', 'default', 0, 1),
(2, '白夜追凶', '悬疑刑侦 · 全32集', 'https://picsum.photos/seed/banner2/1200/600', 9, '', 'default', 1, 1);

INSERT IGNORE INTO mac_cuican_block (id, title, subtitle, layout, position, sort, status, created_at)
VALUES (1, '热播推荐', '编辑精选', 'row', 'home', 0, 1, UNIX_TIMESTAMP());

INSERT IGNORE INTO mac_cuican_block_vod (id, block_id, vod_id, sort)
VALUES (1, 1, 1, 0), (2, 1, 7, 1);

INSERT IGNORE INTO mac_cuican_link (id, title, icon, target_type, target_id, url, sort, status)
VALUES
(1, '电影', 'movie', 'type', 6, '', 0, 1),
(2, '榜单', 'rank', 'url', 0, 'https://example.com/rank', 1, 1);

-- 冒烟测试固定卡：会员 7 天。tests/smoke.sh 兑换一次断言 200、再兑断言 409。
-- 用 ON DUPLICATE KEY 复位，保证 seed 可重复执行、smoke 可反复跑。
INSERT INTO mac_cuican_card (card_no, card_pwd, card_type, card_value, card_status, used_by, used_time)
VALUES ('CARD-TEST-VOID-1', '', 'vip', 7, 0, 0, 0)
ON DUPLICATE KEY UPDATE card_type = 'vip', card_value = 7, card_status = 0, used_by = 0, used_time = 0;

-- 评论：v1.comment 读写 macCMS 自己的 mac_comment（comment_mid=1 影片，comment_rid=vod_id）。
-- 固定 id 900001，避开自增段；user_id=0 走"游客 + comment_name"回退分支。
-- 时间复位成一小时前，重灌 seed 时可重复执行。
INSERT INTO mac_comment
(comment_id, comment_mid, comment_rid, comment_pid, user_id, comment_status,
 comment_name, comment_ip, comment_time, comment_content)
VALUES (900001, 1, 1, 0, 0, 1, '游客', 2130706433, UNIX_TIMESTAMP() - 3600,
        'PC 站与 App 共用 mac_comment，这条演示数据来自 tests/seed_demo.sql')
ON DUPLICATE KEY UPDATE comment_mid = 1, comment_rid = 1, comment_status = 1,
  comment_name = '游客', comment_time = UNIX_TIMESTAMP() - 3600,
  -- 内容也复位：否则用错编码灌过一次的行会一直留着乱码
  comment_content = 'PC 站与 App 共用 mac_comment，这条演示数据来自 tests/seed_demo.sql';

-- 发评论有 30 秒冷却（按 user_id），重灌 seed 时清掉测试号的旧评论，
-- 保证 tests/smoke.sh 的"登录发评论 200"在反复运行时仍然成立。
DELETE c FROM mac_comment c
  JOIN mac_user u ON u.user_id = c.user_id
 WHERE u.user_name = '13900000002';
