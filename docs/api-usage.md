# 客户端接口清单

客户端要连三个入口，其中两个前缀不同。本文说明：用了哪些接口、分别由谁提供、升级时哪一部分会断。

## 0. 三个入口

| 入口 | 前缀 | 提供方 | 契约强度 |
|---|---|---|---|
| 插件接口 | `/icciu_api.php/` | 本仓库的 `server/starveilapi` | **强**：随本仓库演进，改字段必须两端同 PR |
| 苹果 CMS 原生 | `/api.php/` | macCMS 本体 | **弱**：客户端唯一的 CMS 原生依赖，见 §2 |
| H5 支付页 | `/icciu_api.php/v1.user/pay` | 插件 | 中：交给系统浏览器，302 跳网关 |

两个前缀不是同一个 Retrofit 实例（`ApiClient` 里 `retrofit` 与 `cmsRetrofit`），因为成功判定不同：插件接口看 `code == 200`，CMS 原生看 `code == 1`。

## 1. 插件接口（客户端实际调用）

路径写法由 `core/network/.../ApiPaths.kt` 集中定义，共 56 个方法分布在 10 个接口类。

### 1.1 启动、首页内容

| 路径 | 方法 | 接口类 | 说明 |
|---|---|---|---|
| `v1.main/startup` | GET | `StarveilApi` | 启动配置：皮肤、底栏、模块顺序、广告位、支付方式 |
| `v1.main/banner` | GET | `HomeContentApi` | 首页轮播（插件表） |
| `v1.main/category` | GET | `HomeContentApi` | 首页专区，`need_vod=1` 带影片 |
| `v1.main/links` | GET | `HomeContentApi` | 快捷入口 |
| `v1.main/featured` | GET | `HomeContentApi` | 编辑精选 |
| `v1.main/hot` | GET | `HomeContentApi` | 热度榜 |
| `v1.main/guess` | GET | `HomeContentApi` | 猜你喜欢 |
| `v1.main/push` | GET | `HomeContentApi` | 推送条数，只回 `{count}`，用于红点 |

### 1.2 影片

| 路径 | 方法 | 接口类 | 说明 |
|---|---|---|---|
| `v1.vod` | GET | `StarveilApi`、`VodSearchApi` | **一个路径四种语义**：列表 / 榜单 / 筛选 / 搜索，靠 query 区分 |
| `v1.vod/detail` | GET | `StarveilApi` | 详情 + 结构化播放源 |
| `v1.vod/types` | GET | `VodTypeApi` | 分类，带 `extend` 筛选项 |
| `v1.vod/vodphb` | GET | `StarveilApi` | 单榜 |
| `v1.vod/vodPhbAll` | GET | `StarveilApi` | 分类聚合榜 |
| `v1.vod/videoProgress` | GET/POST | `ProgressListApi` | 播放进度读 / 写 |
| `v1.user/videoProgressList` | GET | `ProgressListApi` | 首页「继续观看」最近 10 条 |

`v1.vod` 是最危险的一个：参数写错不会报错，会**静默返回全量列表**。改动必须两端同步。

### 1.3 账号

| 路径 | 方法 | 接口类 | 说明 |
|---|---|---|---|
| `v1.auth/login` | POST | `StarveilApi` | 下发 7 个 cookie |
| `v1.auth/register` | POST | `StarveilApi` | |
| `v1.auth/registerSms` | GET | `StarveilApi` | 注册验证码 |
| `v1.auth/findpass` | GET | `StarveilApi` | 找回密码 |
| `v1.auth/findpasssms` | GET | `StarveilApi` | 找回密码验证码 |
| `v1.auth/deviceLogin` | POST | `DeviceAuthApi` | 设备账号登录（RDA），首次带随机 token 即建号 |
| `v1.user/detail` | GET | `StarveilApi` | 用户资料 |
| `v1.user/index` | POST | `ProfileApi` | 改资料，白名单只有昵称与头像 |
| `v1.upload/user` | POST | `ProfileApi` | 头像上传，multipart 字段名 `file` |

**登出不走后端**：退出只清本地 cookie 与快照，不调用服务端接口。这样不会作废会话，同一账号在其它设备上的登录不受影响。

### 1.4 会员、支付、卡密

| 路径 | 方法 | 接口类 | 说明 |
|---|---|---|---|
| `v1.user/groups` | GET | `StarveilApi` | 会员价目表 |
| `v1.user/order` | POST/GET | `StarveilApi` | 下单 / 查单，**同一路径两种语义** |
| `v1.user/group` | POST | `StarveilApi` | 会员兑换（升级） |
| `v1.user/payTip` | GET | `StarveilApi` | 充值提示 |
| `v1.user/goldTip` | GET | `StarveilApi` | 金币提现说明 |
| `v1.user/goldWithdrawApply` | POST | `StarveilApi` | 提现申请 |
| `v1.upload/withdraw` | POST | `StarveilApi` | 提现收款码，只收 `/upload/withdraw/` 前缀 |
| `v1.user/buy` | POST | `StarveilApi` | 卡密兑换 |
| `v1.user/buypopedom` | POST | `StarveilApi` | 单集购买 |

### 1.5 收藏、试看、播放

| 路径 | 方法 | 接口类 | 说明 |
|---|---|---|---|
| `v1.user/favs` | GET | `StarveilApi` | 收藏列表 |
| `v1.user/ulog` | POST/DELETE | `StarveilApi` | 收藏增删 |
| `v1.user/viewSeconds` | POST | `StarveilApi` | 观看时长上报 |
| `v1.user/checkVodTrySee` | GET | `StarveilApi` | 试看闸门，**唯一的放行判定** |
| `v1.ad/session` | POST | `StarveilApi` | 直投激励视频领会话 |
| `v1.ad/progress` | POST | `StarveilApi` | 播放中上报位置，只记进度不解锁 |
| `v1.ad/complete` | POST | `StarveilApi` | 播完才调，服务端校验后写解锁 |

### 1.6 专题、直播、社区

| 路径 | 方法 | 接口类 | 说明 |
|---|---|---|---|
| `v1.topic/topicList` | GET | `StarveilApi` | |
| `v1.topic/topicDetail` | GET | `StarveilApi` | 影片内嵌成数组，不分页 |
| `v1.zhibo` | GET | `StarveilApi` | 直播列表 |
| `v1.comment` | GET/POST | `CommentApi` | 与 macCMS `mac_comment` 共用（mid=1） |
| `v1.gbook` | POST | `GbookApi` | 留言 / 反馈 |

### 1.7 积分与任务

| 路径 | 方法 | 接口类 | 说明 |
|---|---|---|---|
| `v1.user/sign` | GET/POST | `PointsApi` | 查状态 / 签到 |
| `v1.user/task` | GET/POST | `PointsApi` | 任务列表 / 领奖 |
| `v1.user/shareInfo` | GET | `PointsApi` | 分享信息 |
| `v1.user/shareScore` | POST | `PointsApi` | 每日分享奖励 |

## 2. 苹果 CMS 原生接口（唯一的弱契约）

客户端只用两个 CMS 原生接口，都在 `CmsDanmakuApi`：

| 路径 | 方法 | 说明 |
|---|---|---|
| `/api.php/danmaku/get_list` | GET | 拉弹幕（`vod_id` / `sid` / `nid` / `limit`） |
| `/api.php/danmaku/send` | POST | 发弹幕（`vod_id` / `sid` / `nid` / `time` / `text` / `type` / `color`） |

成功码是 `code == 1`，不是插件那套的 `200`。

**这是客户端唯一依赖 macCMS 本体的地方，也是唯一可能被 CMS 侧改坏的地方。** 以下情况会让弹幕失效，其它功能不受影响：

- 站点关闭或重写了 `api.php`
- 换主题时改了弹幕路由
- 其它插件占用同名路由
- macCMS 大版本升级改了响应结构

弹幕数据在 `mac_danmaku` 表，与插件的 `cuican_*` 表无关。

## 3. 清单之外没有预留

`ApiPaths` 定义的路径就是插件实现的路径，两边一一对应。没有"以后可能要用"的空壳常量，也没有只为兼容某个版本而保留的路由。

新增一个接口要四处同步，缺一处会在运行时静默失败：

| 步骤 | 位置 |
|---|---|
| 1. 注册路由 | `server/starveilapi/src/Kernel.php` 的 `ROUTES` |
| 2. 实现方法 | `server/starveilapi/src/Controller/*Controller.php` |
| 3. 需要登录就加闸门 | 控制器里调 `$this->requireLogin()`。客户端不用登记：`StarveilCookieJar` 对同 host 全量发 cookie，不存在"漏登记就静默变游客" |
| 4. 客户端 | `ApiPaths` 常量 + 接口类方法 + DTO/mapper，并把本文 §1 补上 |

两端必须在同一个 PR 里改，原因见 §4。

### 尚未实现（可作后续计划）

下面这些能力两端都没有，代码里没有半成品可复用。按 §3 开头的四处同步流程接入。

| 能力 | 现状 | 接入要点 |
|---|---|---|
| 私信 | 无 | 列表 + 详情两个接口；`cuican_message` 表已在 schema 里 |
| 群聊 | 无 | 需要实时通道或轮询，现有架构没有 |
| 代理分销 | 无 | 三个接口：绑定上级、下级列表、收益汇总 |
| 用户评分提交 | 详情页只展示 `vod.score`，不接受打分 | 需要防刷，否则单个请求就能把分数刷满 |
| 解析站多线路管理 | `parse2` 是单个字符串字段，多个解析站只能用逗号隔开塞在一个输入框里；客户端只在内部按顺序自动尝试 | 后台改成列表式增删与排序，客户端在播放页给出手动选择入口。注意这与「播放源」不是一层：播放源（`sid`）已经可以手动切换，缺的是解析站选择 |
| 播放器广告位 | `AdGateway` 只覆盖开屏与短剧激励视频 | 需要新的广告位枚举与 provider 实现 |
| 支付页引导图 | 无 | 单接口，图片地址 + 跳转链接 |
| 观看记录单条删除 | 记录页无删除入口 | 接口与 UI 都要加 |

### 有意不做

这几项不是"还没做"，是设计上就不打算做。改动前先看对应理由。

| 项 | 理由 |
|---|---|
| 服务端登出 | 退出只清本地 cookie 与快照，不调服务端接口。这样不会作废会话，同账号在其它设备上的登录不受影响。要改成服务端登出，先确认多端登录的产品预期 |
| 客户端上报浏览量 | 服务端在详情请求里累加，客户端再报一次只会重复计数 |
| 独立的手机号注册入口 | 注册走 `v1.auth/register` + `v1.auth/registerSms`，这条链路本身就是手机号 + 验证码，不需要第二个入口 |
| 等级规则表 | 只展示当前等级值（`UserProfile.level`），不展示等级门槛与权益对照 |
### 「观看记录」用的是哪几个接口

这个功能容易和 `viewLog` 混淆，实际数据源是三条：

| 功能 | 实际接口 |
|---|---|
| 首页「继续观看」（最近 10 条） | `GET v1.user/videoProgressList` |
| 播放位置读写、断点续播 | `GET/POST v1.vod/videoProgress` |
| 观看时长上报 | `POST v1.user/viewSeconds` |
| 观看记录页 | 上面三条的组合，见 `HistoryDestination` |
## 4. 改接口时的规则

**改任何 `v1.*` 路径或字段，客户端与插件必须在同一个 PR 里改。** 两件事会让这个规则变硬：

1. DTO 的 `@JsonNames` 只覆盖少数字段，其余靠字段名精确匹配。改错的后果是反序列化静默降级成 `null`，不报错。
2. 业务错误的 HTTP 状态也是 200，只是壳里 `code != 200`。所以「接口 200 就说明对了」是错的。

新增字段只加不删；要删字段，先确认没有已发布的客户端版本还在读它。

## 5. 路径写法约束

三条不能动：

1. `v1.vod` 这种**点号**是插件按 URL action 反射分发的一部分，写成 `/v1/vod` 直接 404。
2. 大小写有意义：`v1.vod/vodPhbAll` 里的大写 `P` 和 `A` 必须保留，按小写改写会 404。
3. H5 支付页与 App 走同一个入口，登录态靠 cookie 落在同域；换前缀会让 cookie 失效。
