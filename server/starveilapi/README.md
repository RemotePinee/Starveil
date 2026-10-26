# 星幕 API 插件（starveilapi）v2.3.0

macCMS V10 插件。**不改 macCMS 核心文件**，所有表带 `cuican_` 前缀，随核心升级不受影响。

## 1. 安装

1. 把 `server/starveilapi/` 整个目录放到站点 `addons/starveilapi/`。
2. 把 `icciu_api.php` 放到站点根目录（与 `index.php` 同级）。
3. 后台「应用 → 插件」启用 `星幕 API`，启用时执行 `install()`：
   - 建 26 张表（`__PREFIX__cuican_*`，InnoDB + utf8mb4），其中 5 张是 v2.2.0 的首页内容位与短剧解锁表
   - 写默认配置（不覆盖已有值）
   - 生成会话签名密钥
   - 在插件目录写 `install.lock`
4. 配置运营项（`cuican_setting` 表，键值均为字符串）：

| 键 | 默认 | 说明 |
|---|---|---|
| `app_theme_ui` | 1 | 皮肤 1–5 |
| `ui_home_data` | 3 | 首页卡片列数（2 或 3） |
| `app_ui_tab_fun` | 0 | 第二个 tab：1 专题 / 其它直播 |
| `vip_group_id` | 3 | 会员组 id（对应 `mac_group`） |
| `trysee_status` | 0 | 0 免费 / 1 需会员 / 2 需单集付费 |
| `trysee_minutes` | 6 | 试看时长（分钟） |
| `episode_points` | 0 | 单集购买所需积分 |
| `register_require_sms` | 1 | 注册是否必须验证码 |
| `sms_http_url` | 空 | 短信网关模板，支持 `{phone}` `{code}` `{purpose}` 占位 |
| `allowed_origins` | `[]` | CORS 白名单（JSON 数组） |
| `vip_plans` | `[]` | 套餐 JSON，见下 |
| `home_modules` | `["banner","links","blocks","hot","topic","live","guess"]` | 首页模块顺序（JSON 数组），见「首页内容位」 |
| `banner_style` | `1` | 轮播样式；`app_banner_style` 也照常下发 |
| `banner_limit` | `6` | 轮播下发条数上限（1–10） |
| `bottom_tabs` | 五栏默认 | 底部菜单 JSON，见「首页内容位」 |
| `player_info` | 空 | 解析站配置 `{parse2,features,issethead,headers}` |

套餐示例（`vip_plans`）：

```json
[{"id":1,"group_id":3,"period":"month","days":30,"price_yuan":15}]
```

支付通道写 `cuican_pay_channel` 表，`config` 为 JSON：

```json
{"driver":"epay","url":"https://pay.example.com/submit.php","pid":"1000","key":"商户密钥","type":"alipay","notify_url":"https://站点/icciu_api.php/v1.Payment/notify","return_url":"https://站点/"}
```

## 2. 已加固的常见缺陷

以下是同类型 CMS 接口插件反复出现的缺陷，本插件逐条做了处理：

| 已加固的问题 | 本插件的处理 |
|---|---|
| PHP 8 解析错误 `$txt{$i}` | 全部重写，无花括号取字符 |
| 安装器使用未定义变量 | `Installer` 类，`__DIR__` 绝对路径 |
| 建表脚本含 18 条 `DROP TABLE`，进配置页即清库 | 只有 `CREATE TABLE IF NOT EXISTS` |
| 结构脚本写在空处，从不执行 | 结构走 schema 文件 + 版本号 |
| 打包产物被复制到 web 根目录 | 不存在任何复制动作 |
| 覆盖核心 `application/data/update/database.php` | 不碰核心 |
| 无鉴权的积分充值端点 | 删除，路由白名单 |
| 反射分发无白名单 | `Kernel::ROUTES` 显式表 |
| `col` 参数注入可登录任意用户 | openid 字段白名单 |
| 资料整表更新 | 只允许昵称/头像 |
| 上传无鉴权 + 路径拼接 | 本版未实现上传（下一批） |
| 支付不看金额、状态机写反 | 服务端算价 + `0→1` 单向迁移 + 金额校验 |
| 支付回调验签 fail-open | 验签失败一律拒绝 |
| CORS `*` + credentials | 只回显白名单 Origin |
| 注册页不带验证码 | 服务端强制校验，60 秒冷却 + 每日上限 |
| 密码 = Base64(ANDROID_ID) | HMAC 签名会话 + `password_hash`（兼容旧 md5 并升级） |
| 厂商域名、SDK appid、强制更新配置硬编码在种子数据 | 一条不带，全部走配置 |

## 3. 已实现的接口

客户端实际调用的接口与调用方见根目录 [docs/api-usage.md](../../docs/api-usage.md)；这里按插件侧列出。

| 路径 | 说明 |
|---|---|
| `GET v1.main/startup` | 启动配置：皮肤、底栏、模块顺序、广告位、支付方式 |
| `GET v1.main/banner` | 首页轮播（最多 `banner_limit` 条） |
| `GET v1.main/featured` | 编辑精选；未配置或组内无已上架影片时 `vods` 为空数组 |
| `GET v1.main/category?need_vod=1` | 首页专区；`need_vod=0` 时只回专区不回影片 |
| `GET v1.main/links` | 快捷入口（最多 12 条） |
| `GET v1.main/hot` | 首页热度榜（`position='hot'`） |
| `GET v1.main/guess` | 首页猜你喜欢（`position='guess'`） |
| `GET v1.main/push` | 推送条数（`{count:N}`），客户端按 `>0` 冒红点 |
| `GET v1.main/version` | 版本检查（无更新时 data 为 null） |
| `POST v1.auth/login` | 登录，下发 7 个 cookie |
| `POST v1.auth/register` | 注册 |
| `GET v1.auth/registerSms` · `findpassSms` · `findpass` | 验证码与找回密码 |
| `POST v1.auth/deviceLogin` | 设备账号登录（RDA） |
| `GET v1.vod` | 列表；筛选参数见「片库筛选」 |
| `GET v1.vod/detail` | 详情 + 结构化播放源 |
| `GET v1.vod/types` | 分类，每项带 `extend`（`area`/`year`/`class`/`lang` 候选值） |
| `GET v1.vod/vodphb` · `vodPhbAll` | 单榜 / 分类聚合榜 |
| `GET/POST v1.vod/videoProgress` | 播放进度读取 / 上报 |
| `GET v1.topic/topicList` · `topicDetail` | 专题列表与详情 |
| `GET v1.zhibo` | 直播列表 |
| `GET v1.user/detail` · `POST v1.user/index` | 资料读取 / 更新（字段白名单只有昵称与头像） |
| `GET v1.user/favs` · `POST/DELETE v1.user/ulog` | 收藏列表与增删 |
| `POST v1.user/viewSeconds` | 观看时长上报 |
| `GET v1.user/videoProgressList` | 首页「继续观看」最近 10 条 |
| `GET v1.user/checkVodTrySee` | 试看闸门 |
| `GET/POST v1.user/sign` · `v1.user/task` | 签到 / 任务 |
| `GET v1.user/groups` · `POST v1.user/group` | 价目表 / 会员兑换 |
| `GET/POST v1.user/order` | 下单 / 查单，同一路径两种语义 |
| `GET v1.user/pay` | H5 支付页，302 到网关 |
| `GET v1.user/payTip` · `goldTip` | 充值提示 / 提现说明 |
| `POST v1.user/goldWithdrawApply` | 金币提现申请；`qrcode` 必须是 `v1.upload/withdraw` 返回的路径 |
| `POST v1.user/buy` · `buypopedom` | 卡密兑换 / 单集购买 |
| `GET v1.user/shareInfo` · `POST v1.user/shareScore` | 分享信息 / 每日分享奖励 |
| `GET/POST v1.Payment/notify` | 支付回调（Epay 验签） |
| `GET/POST v1.gbook` · `v1.comment` | 留言 / 评论（均带冷却） |
| `GET/POST v1.danmu` | 插件内弹幕接口；客户端另有一套走 CMS 原生 `/api.php/danmaku` |
| `POST v1.upload/user` | 头像上传（multipart 字段 `file`） |
| `POST v1.upload/withdraw` | 提现收款码上传（只收 JPG/PNG/WEBP ≤2MB） |
| `POST v1.shortplay/unlock` | 短剧广告 SSV 解锁（验签 + 幂等） |
| `POST v1.ad/session` · `progress` · `complete` | 直投激励视频：领会话 / 上报进度 / 结算 |
| `GET/POST v1.ad/ssv` | 广告平台 SSV 回调 |

未实现：弹幕举报、金币提现记录查询（后台可看，客户端无入口）。

### 片库筛选

`GET v1.vod` 的筛选参数与 macCMS 前台逐字同名（`application/common.php` 的 `mac_param_url()`）。
客户端把 `v1.vod/types` 下发的 `extend` 原样回传即可，不需要自己拆逗号或猜排序字段：

| 参数 | 含义 | 服务端匹配 |
|---|---|---|
| `type` | 分类 id | `type_id = ?` |
| `class` | 扩展分类名，逗号可多值 | macCMS 语义 `vod_class LIKE '%值%'`，多值 OR；纯数字时仍走老契约 `FIND_IN_SET` |
| `year` | 年份 | `vod_year IN (...)`；另支持 `2010-2018` 区间（源站同样展开） |
| `area` | 地区 | `vod_area IN (...)` |
| `lang` | 语言 | `vod_lang IN (...)` |
| `by` | 排序字段 | 白名单 `id/time/time_add/score/hits/hits_day/hits_week/hits_month/up/down/level`，默认 `time` |
| `order` | 升降序 | `asc` / `desc`，默认 `desc` |
| `wd` | 关键词 | `vod_name LIKE '%wd%'` |
| `level` | 推荐等级 | `vod_level = ?` |

`by` / `order` 都过白名单，非法值落回 `time desc`，客户端字符串永远不进 SQL。

`v1.vod/types` 每一项：

```json
{"type_id":1,"type_name":"电影","type_pid":0,
 "extend":{"area":["大陆","香港"],"year":["2018","2017"],"class":["喜剧","动作"],"lang":["国语","粤语"]}}
```

`extend` 直接读 `mac_type.type_extend`：该列是 **JSON 字符串**（`text`），每个键的值是**逗号串**
（`Extend::areaData()` 等处拿到后 `explode(',', …)`）。JSON 解析失败、或某个键没配，对应字段是
空数组——筛选栏空着，列表照常返回。

与 macCMS 的差异（都是有意为之）：

- `by=duration` / `by=rnd` 不支持：前者源站在 SQL 里把 `"1:35:20"` 换算成秒再排，后者是
  `ORDER BY RAND()`，大表代价不可控；传了会落回默认排序。
- `class` 只做 `LIKE`，不走源站的 Meilisearch 桥接（Vod.php 会先用搜索索引取 id 集合再回填
  `vod_id IN (...)`）；没装搜索引擎时源站也是 LIKE，结果一致。

## 4. 首页内容位

v2.2.0 起，首页的轮播、编辑精选、专区、快捷入口不再依赖 macCMS 的主题/专题模块，全部由插件表管理；
区块顺序与底部菜单走 `cuican_setting`。客户端按 `startup` 下发的 `home_modules` 决定渲染哪些区块及顺序。

**推荐完全独立**：轮播 / 编辑精选 / 热度榜 / 猜你喜欢 / 首页专区这五处只读插件表，
不读 macCMS 的推荐字段 `mac_vod.vod_level`，运营改影片「推荐等级」不会影响新客户端首页。
`v1.vod?level=` 仅为兼容保留（`level=9` 取首页轮播、`level=8` 取分类页）。

### 表结构（`install/schema/`，按版本追加）

| 表 | 用途 | 关键字段 |
|---|---|---|
| `cuican_banner` | 轮播 | `title` `subtitle` `pic` `vod_id` `url` `style` `sort` `status`；`vod_id > 0` 跳详情，为 0 时用 `url` |
| `cuican_block` | 卡片组（首页专区 / 编辑精选） | `title` `subtitle` `layout`（`row` / `grid2` / `grid3`）`position`（`home` 专区 / `featured` 编辑精选）`sort` `status` |
| `cuican_block_vod` | 专区选片 | `block_id` `vod_id` `sort`；`(block_id, vod_id)` 唯一 |
| `cuican_link` | 快捷入口 | `title` `icon` `target_type`（`url` / `vod` / `topic` / `live` / `second`）`target_id` `url` `sort` `status` |
| `cuican_shortplay_unlock` | 短剧广告解锁 | `user_id` `vod_id` `episode_number` `unlock_type` `ad_trans_id`（唯一，防重复回调）`created_at` |
| `cuican_player_ad` | 播放器广告（`install/schema/v2_4_0.sql`） | `title` `ad_type`（`off` / `html` / `video`）`html_code` `html_timeout` `video_url` `click_url` `pause_img` `pause_url` `status` `sort`；取 `status=1` 且 `sort` 最小的一条，无记录时下发 `ad_select=off`；`home_history` 由代码固定为 `true` |
| `cuican_gold_withdraw` | 金币提现申请（`install/schema/v2_0_0.sql`，v2.5.0 加 `qrcode`） | `user_id` `amount` `channel`（1 支付宝 / 2 微信，与客户端 `WithdrawChannel` 一致）`account` `realname` `qrcode`（`/upload/withdraw/...`，空串表示客户端未上传）`status`（0 待审 / 1 通过 / 2 拒绝）`created_at` `handled_at` |

轮播/专区/精选/热度榜/猜你喜欢共用 `cuican_block`（只差 `position`），已全部接后台与接口；`cuican_shortplay_unlock` 接 `v1.shortplay/unlock`，`cuican_player_ad` 接 `v1.main/vodAd`（见下）。

### 启动配置新增字段

`GET v1.main/startup` 新增 `home_modules`、`banner_style`、`banner_limit`、`bottom_tabs`，取值来自 `cuican_setting`：

| 字段 | 默认 | 说明 |
|---|---|---|
| `home_modules` | `["banner","links","blocks","hot","topic","live","guess"]` | 数组顺序即渲染顺序；白名单 `banner` `links` `blocks` `hot` `continue` `topic` `live` `guess`，后台填了未知值会被丢弃 |
| `banner_style` | `1` | 轮播样式；`app_banner_style` 也照常下发 |
| `banner_limit` | `6` | 轮播最多下发几条，服务端夹在 1–10 |
| `bottom_tabs` | 五栏默认配置 | 底部菜单数组，字段见下 |
| `shortplay_type_id` | `0` | 短剧分类（`mac_type.type_id`）。`0` = 未配置，客户端回落到内置常量 `30`；填了以后换站只改后台，不用重新发版 |

`bottom_tabs` 每项字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | `home` / `second` / `rank` / `member` / `mine`；`home` 与 `mine` 必填，缺了后台保存时自动补回 |
| `label` | string | 底栏文案，最长 12 字 |
| `icon` | string | 内置图标名：`home` `rank` `vip`/`member` `user`/`mine` `topic` `shortplay` `live`，未知值客户端退到通用图标 |
| `visible` | bool | `false` 不渲染该栏 |
| `order` | int | 越小越前，后台保存后按它排序 |
| `mode` | string | 只对 `second` 有效：`topic` / `live` / `shortplay` |

后台保存 `bottom_tabs` 时丢弃未知 `id` 与重复 `id`；配置缺失或解析失败时，客户端回落到默认五栏。

### 启动配置：广告、详情页与推送

| 字段 | 默认 | 说明 |
|---|---|---|
| `ads.application_id` | 空 | 广告 SDK AppID，取 `ad_sdk_appid`；客户端读这个键（`ads.sdk_appid` 也照发，保持兼容） |
| `ads.startup_adv` | 空 | 启动图 HTML，取 `ad_startup_html`；`description` 为空即不展示 |
| `ads.xunfei_appid` | 空 | 讯飞 AppID，取 `xunfei_appid`；为空即关闭 |
| `ads.sdk_excitation` | 关 | 激励视频：`ad_enabled=1` **且** `ad_slot_reward` 非空才下发 `status=1` |
| `player_ad` | 空结构 | 播放器广告，`PlayerAd::payload()`；后台「播放器广告」 |
| `detail_sections` | 全开 | `{recommend,score,comment}`，取后台「运营设置 → 详情页」三个开关 |
| `push` | `0` | 推送条数，客户端按 `>0` 冒红点 |
| `push_enabled` | `1` | 为 `0` 时客户端不展示推送入口 |

### 短剧广告解锁（SSV）

`POST v1.shortplay/unlock`，需要登录（未登录 `code=401`）。客户端 `AdGateway` 仍是 Noop，
本接口先按服务端契约就绪，可直接用 curl 验证。

| 参数 | 说明 |
|---|---|
| `vod_id` | 影片 id（`mac_vod.vod_id`） |
| `episode_number` | 集数，从 1 开始 |
| `trans_id` | 广告平台回调的交易号；唯一索引去重，重复回调不再解锁 |
| `timestamp` | Unix 秒；与服务端相差超过 **600 秒**视为过期 |
| `sign` | 见下方公式 |
| `user_id` | 可传但**不采信**，一律取登录会话 |

```
sign = md5(trans_id + '|' + vod_id + '|' + episode_number + '|' + timestamp + '|' + ad_ssv_secret)
```

`ad_ssv_secret` 是 `cuican_setting` 里的设置项，在后台「运营设置 → 广告 → SSV 密钥」填写，
默认空字符串（空时接口一律 403）。**后台不回显该密钥**：密码框始终为空，留空保存表示保持原值。

返回：成功 `{"code":200,"msg":"success","data":{"url":"...","unlocked":true}}`；
重复 `trans_id` 返回 `code=200`、`unlocked=false`，`cuican_shortplay_unlock` 仍只有一条记录；
签名错误/过期/未配置密钥 `code=403`；影片或该集不存在 `code=404`。
业务错误按项目约定仍是 HTTP 200（见 §2）。

## 5. 验证状态

### CI 查什么

`.github/workflows/plugin.yml` 在改动 `server/**` 时跑三件事：

1. 全量 `php -l` 语法检查（PHP 8.1）
2. 入口文件 `server/icciu_api.php` 存在
3. `Starveilapi.php` 存在（类名与目录名必须同构）

### CI 为什么只做语法检查

接口回归**故意不放进 CI**。`tests/` 下三套脚本都需要一个真装好的站点：

| 脚本 | 前置条件 |
|---|---|
| `tests/smoke.sh` | macCMS V10 + MySQL 5.7.40 + 插件 Installer 已跑完 + 导入 `seed_demo.sql` |
| `tests/admin.sh` | 同上，且后台可通过 HTTP 访问 |
| `tests/notify.sh` | 同上，且支付通道已配好 |

在 CI 里铺这套环境要：拉 macCMS 全量源码、跑它的安装向导（写配置文件 + 建库）、配 PHP-FPM 与 Nginx 的 `PATH_INFO` 伪静态、导入种子数据。维护成本高于它能挡住的回归风险；而环境一旦和线上不一致，跑出来就是**假绿**——比没有 CI 更危险。

所以口径是：**CI 绿只代表"语法没错、文件都在"，不代表接口可用。**

### 改 PHP 逻辑时的要求

改动 `server/starveilapi/**` 里业务逻辑的 PR，**必须在本地跑一次 `tests/smoke.sh`，并把 PASS/FAIL 贴在 PR 描述里**。只改注释、文档或纯格式不用。

脚本用法见 §7「实测记录（本地容器）」，常用两条：

```bash
SITE=/var/www/html PHP_CMD="docker exec starveil-web php" bash tests/admin.sh
BASE=https://<测试站>/icciu_api.php bash tests/smoke.sh
```

### 首次部署前的自检

真实建表与请求在目标环境跑一遍：

```bash
php -l server/starveilapi/src/Kernel.php
php -l server/starveilapi/install/Installer.php
curl -s https://站点/icciu_api.php/v1.main/startup | head
```

## 6. 环境要求与实测兼容性

**本插件按当前 macCMS V10 源码逐项核对过**（`magicblack/maccms10` master，内置 ThinkPHP **5.0.24**）：

| 事实（源码位置） | 对插件的影响 | 处理 |
|---|---|---|
| 插件类名规则：目录名 → `ucfirst` 类名（`get_addon_class`） | 目录 `starveilapi` 必须配 `Starveilapi` 类与同名文件 | 已改名 `Starveilapi.php` |
| 插件基类实际是 `vendor/karsonzhang/fastadmin-addons` 的 `think\Addons` | `use think\Addons` 可用 | 保持 |
| 后台安装会执行 `addon->install()`，随后 `importsql()` 找 `install.sql` | 没有 `install.sql` 时跳过 | 我们用 `Installer`，不依赖它 |
| 安装会把 addon 的 `application/`、`static/` 复制到站点根 | 插件里放这两个目录会污染核心 | 插件内**没有**这两个目录 |
| `mac_user.group_id` 是 **varchar(255)**，允许逗号多组 | `(int)group_id` 判断会漏 | VIP 判定与写回都按逗号列表处理 |
| 密码校验统一走 `mac_verify_password()` / `mac_hash_password_for_column()`（兼容 bcrypt、两种 md5、极少数明文） | 自己实现会与后台不一致 | 有函数时直接调用，缺失才回退 |
| 积分列是 `mac_user.user_points` | 另存一份后台看不到 | 积分读写走原生列，插件表只存金币/观看时长/代理 |
| `mac_type` 没有 `type_img`，封面叫 `type_pic` | 原写法会 SQL 报错 | 已改 `type_pic`，输出仍叫 `type_img`（客户端契约） |
| `mac_vod.vod_class` 是逗号串 | 等值匹配会漏/误命中 | 分类名筛选走 `LIKE`（与源站 `mac_like_arr` 一致）；老 `class` 数字入参仍走 `FIND_IN_SET` |
| TP 5.0.24 `Query` 有 `inc()` / `whereRaw()` / `whereIn()` / `page()` | 无需改写法 | 已确认存在 |
| `Db::quote()` 在 Connection 上，facade 转发可用 | 安装器拼 SQL 安全 | 保持 |
| `application/common.php` 由 `App::initCommon()` 载入 | 入口必须走 `initCommon()` 才能拿到 `mac_*` 助手函数 | 入口已改为 `base.php + initCommon()` |

**环境下限**：PHP **8.1+**（用了 `readonly` 构造器属性、`match`、`str_starts_with`）、macCMS V10、MySQL 5.7+/8.0（utf8mb4）。PHP 8.0 会在 `readonly` 处解析失败，不要往下兼容。

### 部署注意（否则接口 404）

- `icciu_api.php` 必须放在**站点根**，与 `index.php` 同级。
- 插件目录名必须是 `starveilapi`（与 `info.ini` 的 `name`、类名三者一致）。
- 路径形如 `/icciu_api.php/v1.vod/detail`。Nginx 需要把 `PATH_INFO` 传给 PHP：

```nginx
location ~ ^/icciu_api\.php(/|$) {
    fastcgi_pass  unix:/run/php/php8.1-fpm.sock;
    fastcgi_param SCRIPT_FILENAME $document_root/icciu_api.php;
    fastcgi_param PATH_INFO $fastcgi_path_info;
    include       fastcgi_params;
}
```

Apache + `mod_php` 默认即可（`AcceptPathInfo` 默认 On）。

### 卸载

后台卸载只删插件目录，**不删数据表**（防止误点丢订单）。要彻底清理由 DBA 执行：

```sql
-- 先备份，再执行
SET @p = 'mac_';  -- 换成真实前缀
-- 逐表确认后再 DROP，插件不提供一键删库
```


## 7. 实测记录（本地容器）

环境：WSL Docker + `mysql:8.0`（utf8mb4）+ `php:8.1-cli`（装了 pdo_mysql / mbstring / curl）+ 当前 macCMS master 源码，
插件按 `addons/starveilapi` 放置、入口放站点根，`Installer::install()` 建表，
导入 `tests/seed_demo.sql` 后跑 `tests/smoke.sh` 与 `tests/notify.sh`。

| 用例 | 结果 |
|---|---|
| 启动配置、价目表、影片列表/详情/分类/单榜/聚合榜 | 通过 |
| 专题列表/详情（含内嵌影片）、直播列表 | 通过 |
| 注册、登录、cookie 会话、用户详情、资料更新、**越权字段被忽略** | 通过 |
| 收藏增/查/删、试看、观看时长、播放记录 | 通过 |
| 弹幕发送（含 10 秒冷却）/拉取 | 通过 |
| 充值提示、金币提示、下单、查单 | 通过 |
| 支付页 `/v1.user/pay` 302 跳网关（已支付订单返回 409） | 通过 |
| 支付回调：正确签名 → success；重复回调/错误金额/错误签名 → fail | 通过 |
| 会员到账：`mac_user.group_id` 变为 `1,3` 且 `user_end_time` 顺延 30 天 | 通过 |
| 金币提现：足额提交成功、低于门槛被拒 | 通过 |
| 卡密：首次兑换成功、重复使用被拒 | 通过 |
| 单集购买：购买成功、试看接口随之放行 | 通过 |
| 短剧 SSV：未登录 401、错签/过期 403、成功换签、重复 `trans_id` 幂等、缺集 404 | 通过 |

`tests/smoke.sh` 最终 **PASS=70 FAIL=0**（在容器内用 `sh tests/smoke.sh` 执行）。

### 实测中发现并修复的问题（都已在仓库代码里）

1. `Kernel.php` 注释里的 `get*/post*/delete*` 提前闭合了块注释 → PHP 解析错误。已改写文案。
2. `Installer` 放在 `install/` 但命名空间是 `Starveil\Api` → 自动加载找不到类。已移到 `src/Installer.php`。
3. **TP 5.0 的 `clone Query` 做 `count()` 会污染 bind**，之后执行主查询报 `Invalid parameter number`。所有列表改为"两次独立构建 Query"。
4. 路由正则只接受小写控制器，`v1.Payment/notify` 直接 404。已放宽为 `[A-Za-z]+`。
5. 回调验签把**我们自己的路由参数 `pay_type`** 算进了签名串，导致真实回调永远验不过。已剔除 `pay_type`/`channel`。
6. 客户端契约对齐：下单只传 `price`（服务端按价格反查套餐，防止改价）、`order_price` 必须是字符串、卡密只有一个输入框（卡号即卡密）、补齐 `v1.user/groups`、`payTip`、`goldTip`、`goldWithdrawApply`、`v1.topic/*`、`v1.zhibo`。
7. FastAdmin 插件类名必须与目录名同构，类名 `Starveilapi` 与目录 `starveilapi` 必须一致；实测 `new addons\starveilapi\Starveilapi()` 与 `getInfo()` 正常。
8. **客户端打开的 H5 支付页 `/v1.user/pay` 服务端没实现**：客户端把 `?payment=&order_code=` 交给系统浏览器，服务端原先 404。已补 `getPay`：凭随机订单号定位、未支付才 302 到网关，已支付返回 409。

### 还没覆盖的

- 后台「应用市场 → 启用」这条 UI 路径没点过（直接调用了 `Installer::install()`）；`Service::install` 的复制/回滚逻辑仅按源码核对。
- 支付只实测了 Epay；微信/支付宝原生参数未接。
- 数据库只在 MySQL 5.7.40 上跑过，8.0 未测。
- 未压测、未做并发下单/重复回调竞争测试。


## 8. 插件后台与后台菜单

### 菜单挂在哪

macCMS 的后台左侧菜单来自 `application/extra/quickmenu.php`（后台「自定义菜单配置」编辑的就是它），
顶部「应用」页只是 FastAdmin 的插件列表。**插件不会自动出现在左侧菜单**，所以 `install()`
会往 quickmenu 里合并一条：

```
星幕 API,/addons/starveilapi/admin.php
```

- 重复启用不会重复添加（先按 URL 去重再插入）
- 卸载时只移除自己这一条，运营自定义的菜单不动
- 需要 `application/extra/` 可写（macCMS 安装本身就要求）

已经装过旧版本、左侧没入口的，二选一：

1. 后台「应用 → 应用市场」里点 **禁用 → 启用**，会重跑安装器补上；
2. 后台「系统 → 自定义菜单配置」手动加一行 `星幕 API,/addons/starveilapi/admin.php`；
3. 或者跑插件自带工具（推荐，顺手清缓存）：

```bash
php addons/starveilapi/tools/fix-menu.php
# 已写入 .../application/extra/quickmenu.php
# 缓存已清理
```

### 从旧版本升级到 v2.2.0

覆盖插件目录后执行一次安装器，即补跑 `v2_2_0.sql`（5 张新表 + `home_modules` / `banner_style` / `banner_limit` 默认值）：

```bash
php addons/starveilapi/tools/install.php
```

也可以后台「应用 → 插件」禁用再启用（启用走同一个 `install()`）。迁移幂等，只执行 `CREATE TABLE IF NOT EXISTS`，不清空已有数据。

注意：单独打开插件后台不会补跑新版本迁移 —— 入口里的自愈只在关键表整体缺失时触发，升级后请执行上面这条命令。

### 表没建/后台报 1146

后台能打开但插件页报 `Table ... doesn't exist`，说明启用时 `install()` 没跑成功。补跑：

```bash
php addons/starveilapi/tools/install.php
# 安装成功（表结构 / 默认配置 / 菜单）
# 数据表：26 张（前缀 mac_cuican_）
# 订单表：存在
```

幂等，不清空已有数据。

### 后台页面

独立入口：`/addons/starveilapi/admin.php`（复用 macCMS 后台登录态，不依赖伪静态）。

| 页面 | 能做什么 |
|---|---|
| 概览 | 注册用户、已支付订单、待审核提现、未使用卡密、弹幕数 |
| 运营设置 | 皮肤、首页列数、试看、积分、提现门槛、短信网关、CORS、套餐、解析站、代理等级、热词、广告（含 SSV 密钥，密码框不回显） |
| 轮播 | 增删改首页轮播：标题/副标题/图片、影片 ID 或外链、排序、启停（`?a=banner`） |
| 编辑精选 | 维护唯一一组 `position='featured'`：标题/副标题/启停、按影片 ID 批量加入、上移/下移/移除（`?a=featured`） |
| 热度榜 | 维护 `position='hot'` 的卡片组：标题/副标题/启停、按影片 ID 批量加入、上移/下移/移除（`?a=hot`） |
| 猜你喜欢 | 维护 `position='guess'` 的卡片组，操作同上（`?a=guess`） |
| 首页专区 | 新建专区（横滑/双列/三列）、按影片 ID 批量选片、删除（`?a=blocks`） |
| 快捷入口 | 标题/图标/跳转类型（url/vod/topic/live/second）/排序（`?a=links`） |
| 模块与底栏 | 改 `home_modules` 顺序、`bottom_tabs` JSON（`?a=tabs`） |
| 卡密 | 批量生成（会员天数 / 金币）、查看使用状态 |
| 专题 | 新建、删除、绑定影片 |
| 直播 | 新增、删除 |
| 播放器广告 | 广告位选择、HTML 代码与超时、角标地址/点击跳转、暂停图/跳转、历史入口（`?a=playerad`） |
| 推送 | 发布推送（标题 + 内容）并查看条数（`?a=push`） |
| 版本 | 维护 `v1.main/version` 返回的版本信息（`?a=version`） |
| 游戏位 | 维护 `v1.youxi/index` 的游戏位配置（`?a=youxi`） |
| 支付 | 通道增删改（易支付）、启用/停用、回调地址一键复制 |
| 提现审核 | 通过 / 拒绝（拒绝自动退回金币） |
| 订单 | 最近 50 条与支付状态 |
| 用户 | 按账号搜索、增减积分与金币 |

「首页装修」七页入口都在插件后台，直接改 `a=` 即可：
`/addons/starveilapi/admin.php?a=banner`、`?a=featured`、`?a=hot`、`?a=guess`、`?a=blocks`、`?a=links`、`?a=tabs`。
播放器广告、推送、版本、游戏位同理：`?a=playerad`、`?a=push`、`?a=version`、`?a=youxi`。
左侧快捷菜单仍只有一条「星幕 API」（走 quickmenu），这些页在插件后台顶栏切换。

后台是单页交互：切 Tab 走 fetch 局部刷新，不再整页重载（旧版每切一次都闪一下）；
表单统一 AJAX 提交 + 顶部进度条 + 底部 toast，写操作带 CSRF token。

安全：非 CLI 强制 `checkLogin()`（失败回 403 登录提示，不抛错页）；写操作要求 POST + CSRF token；
输出统一转义；SQL 全走查询构造器。CLI 模式仅供本地自检（`php addons/starveilapi/admin.php dashboard`），HTTP 不暴露。

### 后台自检脚本

```bash
SITE=/var/www/html PHP_CMD="docker exec starveil-web php" bash tests/admin.sh
# PASS dashboard / settings / cards / topics / live / withdraw / orders / users
```
