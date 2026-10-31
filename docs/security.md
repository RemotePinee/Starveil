# 安全基线

本文件是**硬约束**。`verifySecurityBaseline` Gradle 任务会扫描源码与清单，
命中黑名单直接构建失败。文档中标注「人工评审」的项，机器查不出来，必须走评审。

## 0. 先说清楚边界

客户端能控与不能控必须分开，否则会写出"看起来安全"的假象（常见反模式：
用 RC4 加密域名，而密钥就躺在 APK 里）。

| 能力 | 归属 | 说明 |
|---|---|---|
| 传输加密、证书校验、请求头注入防护 | 客户端 | 本册强制 |
| 本地存储加密、日志脱敏、WebView 收紧 | 客户端 | 本册强制 |
| 接口鉴权、越权校验、支付验签、限流 | **服务端** | 同类后端常见 3 处可直接利用的越权，见本文件第 10 节；客户端不能替代 |
| 内容版权、分发合规 | 运营 | 不在工程范围 |

**客户端不做"伪安全"**：不实现自定义加密协议、不做客户端签名校验业务、不依赖
"参数里塞个签名就安全"（常见实现里 `apikey` 后端根本不校验）。

## 1. 传输安全

- 全站 HTTPS。Release 构建**禁止明文流量**：由 `network_security_config` 的
  `<base-config cleartextTrafficPermitted="false">` 保证，`AndroidManifest.xml`
  里的 `android:networkSecurityConfig` 指向它，并用 `tools:replace` 顶掉广告 SDK
  合并进来的同名配置（TradPlus 自带一份含明文放行项的）。
  部署方必须自备证书并全站 HTTPS；`local.properties` 的 `starveil.apiHost` 只能写
  `https://`，站点侧也应把 80 端口 301 到 443。
  debug 变体保留信任用户 CA（`app/src/debug/res/xml/`），便于本地抓包；Release 不放行。
- 引导域名（bootstrap）与运行时下发 API 地址：**当前未实现**。
  `starveil.apiHost` 在构建期注入成资源字符串，客户端不向服务端请求域名、
  也没有签名校验环节。要改后端地址只能重新打包。后续若要做下发，必须带
  Ed25519 / RSA-PSS 签名且客户端只存公钥，不接受未签名响应——在那之前不要在
  文档里把它写成已有能力。
- 证书异常（`onReceivedSslError`、`TrustManager`）**一律不得 proceed**。
  机器校验黑名单：`TrustAllCerts`、`AllowAllHostnameVerifier`、`X509TrustManager` 空实现、
  `MIXED_CONTENT_ALWAYS_ALLOW`。
- 不做证书固定（pinning）：业务域名由配置注入且可能变更，pin 会导致不可用。
  将来若启用域名下发，可只对下发源做 pin（第二阶段评估）。

## 2. 凭据与密钥

- 仓库内**不得**出现任何真实密钥：签名口令、API key、广告 SDK key、友盟 key、
  推送 key、支付私钥。全部走 `local.properties`（不入库）或 CI 环境变量。
- 签名配置从环境读取，缺失时 Release 构建**失败**而不是用调试签名兜底。
- `apikey`/`keytime` 按契约携带，但代码注释必须写明"后端不校验，不是安全机制"。
- 混淆后不得在字符串常量里保留密钥（R8 不会加密字符串）。

机器校验黑名单（`kt`/`xml`/`gradle` 文件）：
`password = "…"`、`apiKey = "…"`、`secret = "…"`、`storePassword "…"`、
`keyPassword "…"`、`http://` 硬编码（允许 `127.0.0.1`、`localhost`、`schemas.android.com`）。

## 3. 本地存储

- Cookie（含 `user_check` 会话串）存 DataStore，**不写日志**。
- 令牌类数据首期不加密，但必须：仅存必要字段、退出登录即清除、不做跨设备同步。
  第二阶段评估 EncryptedSharedPreferences 或 Android Keystore 包装。
- 禁止把用户手机号、真实姓名、提现账号写入本地明文库
  （提现申请表在同类服务端普遍是明文存储，那是服务端问题，客户端不跟着存）。
- 播放进度、搜索历史可存，但需提供一键清除入口。

## 4. WebView

解析 WebView 若全开（JS + 文件访问 + 混合内容 + 证书放行 + 任意 URL），是整个客户端
最高危的部分。新规则：

- `addJavascriptInterface` **禁止**（机器校验）。
- 文件访问全关：`allowFileAccess = false`、`allowContentAccess = false`，
  不启用 `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs`。
- 混合内容：`MIXED_CONTENT_NEVER_ALLOW`。
- SSL 错误一律 `handler.cancel()`，不接受"证书错误放行"（常见实现是 `handler.proceed()`）。
- 多窗口、页面自开窗口、定位、DOM Storage 全部关闭；媒体需用户手势。
- 解析优先走原生 HTTP（OkHttp + 正则提取），**WebView 仅作兜底**，
  实现见 `app/src/main/kotlin/com/starveil/app/probe/RestrictedWebProbe.kt`：
  - 解析页必须是 https 且非内网/本机地址（`core:model` 的 `ParseHostPolicy`，带单测）；
  - 嗅探到的资源地址同样过 `ParseHostPolicy`；
  - 单线路 15 秒超时，结束即 `destroy()`，不常驻、不复用；
  - `shouldOverrideUrlLoading` 对不合格 host 直接拦截（不交给系统浏览器，
    避免把不可信地址再递给外部应用）。
- 白名单目前是"https + 非内网"，不再依赖服务端下发域名列表；后端若将来下发，
  必须逐个过同一策略。
- 兜底路径的可观测性走事件/埋点，不用裸日志（裸日志被机器校验禁止）。
- 第三方页面不得读取本地 Cookie：`CookieManager` 按域名隔离，不调用
  `setAcceptThirdPartyCookies(true)`；支付页不在 App 内 WebView 打开，
  交给系统浏览器。

## 5. 下载与安装

- 应用内更新**不做静默安装**：不申请 `REQUEST_INSTALL_PACKAGES`，
  下载完 APK 后通过 FileProvider + `ACTION_VIEW` 交给系统安装器，由用户确认。
- 下载文件必须校验：HTTPS + 服务端提供 SHA-256（或签名），不匹配即删除。
- 下载目录用应用私有目录或 MediaStore，**不写外部存储根目录**
  （写 `/sdcard/apkDownload.apk` 是不合规做法）。
- FileProvider 的 `authorities` 只用 `${applicationId}.fileprovider`，
  `paths` 最小化（只暴露下载子目录）。

## 6. 权限最小化
白名单上限（`build.gradle.kts` 的 `allowedPermissions`，机器校验）：

```
INTERNET
ACCESS_NETWORK_STATE
ACCESS_WIFI_STATE         (聚合广告 SDK 判定网络类型与信号强度)
POST_NOTIFICATIONS        (Android 13+，播放通知需要)
FOREGROUND_SERVICE        (后台播放)
FOREGROUND_SERVICE_MEDIA_PLAYBACK
WAKE_LOCK                 (后台播放保持屏幕唤醒)
```

**这只是上限，不等于当前已声明**。`AndroidManifest.xml` 现在实际只写了四项：
`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`AD_ID`。
播放通知与前台服务尚未实现，等做的时候再从白名单里取用，不要提前往 manifest 里加。

```

白名单之外还有一项由 SDK manifest 合并进来、本仓库 manifest 也显式声明的
`com.google.android.gms.permission.AD_ID`（Google 广告 ID，境外广告源必需）。
闸门的判定是"整行包含 `android.permission.` 才抽取
`android\.permission\.[A-Z_0-9]+`"（`build.gradle.kts:222-225`），
`com.google.android.gms.permission.AD_ID` 不含该子串，所以它不会进入权限集合，
也就无须进白名单。**这不是漏配**：改那条正则时要连同这里一起看。

广告 SDK 合并进 APK 的权限不在闸门校验范围内（闸门只扫源码里的 manifest，
不进 `build/`）。上表因此是"我们主动声明的上限"：若上游合并出
`READ_PHONE_STATE` 这类项，PR 里必须贴 `processReleaseMainManifest` 的合并结果再评审。

明确**不申请**：`READ_PHONE_STATE`、`READ/WRITE_EXTERNAL_STORAGE`、
`ACCESS_FINE/COARSE_LOCATION`、`SYSTEM_ALERT_WINDOW`、`REQUEST_INSTALL_PACKAGES`、
`RECORD_AUDIO`、`CAMERA`。

广告带来的取舍：TradPlus 接的几个三方广告源要 `READ_PHONE_STATE`（设备标识）
与 `REQUEST_INSTALL_PACKAGES`（直接拉起安装）才给更高的出价。本仓库一律不申请，
代价是这些源的填充率与 eCPM 低一档。要放开就必须产品决策，而且三处一起改：
manifest 声明、`allowedPermissions` 加条目、本节同步；缺一处 `check` 就红。

需要在历史功能上取舍时的处理：

| 历史功能 | 依赖权限 | 新方案 |
|---|---|---|
| 悬浮窗画中画 | `SYSTEM_ALERT_WINDOW` | 改用系统 PiP（`enterPictureInPictureMode`），无需该权限 |
| 语音搜索/语音弹幕 | `RECORD_AUDIO` | 首期不做；确需时运行时申请 + 说明用途 |
| 扫码登录 | `CAMERA` | 首期不做 |
| 应用内更新 | `REQUEST_INSTALL_PACKAGES` | 跳浏览器下载，不申请 |
| 本地下载缓存 | 存储权限 | 应用私有目录 + MediaStore |

## 7. 第三方 SDK

每个 SDK 引入前必须回答：能否自建替代、是否可延迟初始化、是否可关闭个性化广告。

| SDK | 现状 | 要求 |
|---|---|---|
| 广告 · TradPlus（`com.tradplusad:tradplus` 16.9.0.1 + sigmob/mimo/oppo/vivo adapter） | 唯一的聚合壳。依赖只出现在 `:app`，`AdProviders` 里按 `platform.ready` 决定是否启用 | 采集：设备标识（受系统权限约束）、网络状态、WiFi 信息；**可延迟初始化**——`AdConsentStore.isGranted` 为假时完全不 init，不得在 `Application.onCreate` 无条件跑；用户拒绝隐私后降级成直投素材（`LocalAdGateway`），不采集任何信息；SDK 只出现在 `:app` |
| 崩溃/统计 | 未接入 | 可替换，优先自建或选可关闭采集的方案 |

SDK 初始化必须在用户同意隐私政策之后（`AppContainer` 里做开关），
不得在 `Application.onCreate` 无条件初始化（常见实现就是无条件）。

## 8. 日志与调试

- Release 关闭所有调试日志与 `BuildConfig.DEBUG` 分支。
- 禁止打印：Cookie、`user_check`、手机号、用户 ID、播放地址、解析站 URL、支付订单号。
- 禁止保留 `android:debuggable="true"`、`android:allowBackup="true"`（备份会带走 Cookie）。
- 禁止 `System.exit` / `Runtime.exit`（用它做"盗版检测"，误杀且不可控）。
- 崩溃上报需脱敏，上报前过滤 URL 参数。

## 9. 混淆与完整性

- Release 必须 `isMinifyEnabled = true` + `isShrinkResources = true`。
- 保留规则集中在 `app/proguard-rules.pro`，禁止 `-ignorewarnings` 掩盖真实问题。
- 不做客户端"防破解自毁"逻辑：4 字段校验失败即 `System.exit` 的做法，
  既误杀正常用户，也拦不住改包。改为服务端鉴权与功能降级。

## 10. 服务端职责（客户端无法替代）

以下几项只能由服务端保证。本插件的处理方式逐条列在
[`server/starveilapi/README.md`](../server/starveilapi/README.md) §2「已加固的常见缺陷」：

| 项 | 插件侧 |
|---|---|
| 登录参数注入 | 没有 openid / 列名参数入口，登录只按 `user_name` 查库 |
| 资料越权写 | 只允许改昵称与头像 |
| 上传鉴权与路径拼接 | 固定目录 + 随机文件名 + 大小限制 |
| 支付金额与回调重放 | 服务端算价 + `trans_id` 唯一索引 |
| 回调验签 | 验签失败一律拒绝，不 fail-open |

仍然需要部署方自己确认：

- `apikey` / `keytime` 服务端**不校验**，客户端携带它只为兼容契约，**不要当作安全机制**
- 接口限流、日志留存、证书与 HTTPS（见 §1）

## 11. 机器校验映射

| 规则 | 检查方式 |
|---|---|
| 明文流量、`TrustAll`、`addJavascriptInterface`、`System.exit`、WebView 放开、硬编码密钥、明文 URL、权限白名单 | `verifySecurityBaseline`（构建失败） |
| 日志脱敏、SDK 延迟初始化、混淆开关 | `verifySecurityBaseline` + 人工评审 |
| 签名从环境读取、Release 不得用 debug 签名 | Gradle 构建脚本逻辑 |
| 后端 6 项 | 服务端整改清单，不在本仓库 |
