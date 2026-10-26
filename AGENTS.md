# Starveil — 工程硬约束（AI 与人类共同遵守）

> 本文件是**规则**，不是介绍。凡与本文件冲突的临时想法、示例代码、第三方教程，一律以本文件为准。
> 可执行部分已落到 Gradle 任务（`verifyFileLength`、`verifyNoHardcodedColors`、`verifyModuleGraph`、
> `verifySecurityBaseline`），违反会在 `./gradlew check` 阶段直接失败。
> 文档没写但检查器能查的，以检查器为准。

## 0. 提交前唯一闸门

```bash
./gradlew check
```

必须全绿。它聚合：编译、单元测试、Android Lint、detekt，以及四个规范闸门
（文件行数、颜色令牌、依赖方向、安全基线），共五道。任何一项红，视为未完成。

五道闸门的实现：

| 闸门 | 任务 | 配置 |
|---|---|---|
| 文件行数 450（目标 400） | `verifyFileLength` | 根 `build.gradle.kts` |
| 颜色令牌 | `verifyNoHardcodedColors` | 根 `build.gradle.kts` |
| 依赖方向 | `verifyModuleGraph` | 根 `build.gradle.kts` |
| 安全基线 | `verifySecurityBaseline` | 根 `build.gradle.kts` |
| 类/函数/复杂度 | detekt | `config/detekt/detekt.yml` |

## 1. 模块与依赖方向（机器校验）

```
:app            应用壳：Application / MainActivity / NavHost / DI 容器 / Manifest
:feature:home   页面级功能（每个 feature 一个模块）
:feature:shortplay  剧场页（短剧海报墙；竖屏播放仍归 :feature:player）
:core:designsystem  设计令牌 + 主题 + 原子组件
:core:data          仓库实现、本地存储、缓存
:core:network       Retrofit 接口、DTO、拦截器、Cookie 存储
:core:model         纯 Kotlin 领域模型
:core:common        纯 Kotlin 公共设施（AppResult、AppError、调度器抽象）
```

允许的依赖（**只有这些**）：

| 模块 | 允许依赖 |
|---|---|
| `:app` | 任意 `:feature:*`、任意 `:core:*` |
| `:feature:*` | `:core:designsystem`、`:core:data`、`:core:model`、`:core:common` |
| `:core:data` | `:core:network`、`:core:model`、`:core:common` |
| `:core:network` | `:core:model`、`:core:common` |
| `:core:model` | `:core:common` |
| `:core:designsystem` | `:core:common` |
| `:core:common` | 无（除标准库与 coroutines） |

绝对禁止：

- `:core:*` 依赖 `:feature:*` 或 `:app`
- `:feature:A` 依赖 `:feature:B`（跨页面跳转靠导航，不靠模块依赖）
- `:core:model` 依赖 Android SDK、Retrofit、Compose
- 任何模块依赖 `:app`
- 环形依赖

## 2. 文件与函数规模（机器校验 + detekt）

| 项 | 上限 | 校验方式 |
|---|---|---|
| 单个 `.kt` 文件 | 400 行（硬上限 450） | `verifyFileLength` |
| 单个类 | 200 行 | detekt `LargeClass` |
| 单个函数 | 40 行 | detekt `LongMethod` |
| 参数个数 | 5 | detekt `LongParameterList` |
| 嵌套深度 | 3 | detekt `NestedBlockDepth` |
| 圈复杂度 | 10 | detekt `CyclomaticComplexMethod` |
| 单个 Compose `@Composable` | 80 行 | 人工评审（detekt 不识别 Composable） |

超限就拆：提取子函数、提取组件、提取状态持有者。**不允许用 `@Suppress` 绕过**。
确需例外时，在 PR 描述中写明理由，并在 `config/detekt/baseline.xml` 登记。

## 3. 颜色与视觉令牌（机器校验）

- 颜色字面量 `Color(0x...)`、`#RRGGBB` **只允许**出现在
  `core/designsystem/src/main/kotlin/com/starveil/core/designsystem/token/` 下。
- 业务与页面代码只能用 `MaterialTheme.colorScheme.*` 或 `StarveilTheme.colors.*`。
- 间距、圆角、字号、动效时长同样走令牌，见 `docs/design-system.md`。
- 禁止 `android.graphics.Color.parseColor`。
- 禁止在 `res/values/colors.xml` 里新增颜色（Android 侧仅保留启动主题必需项）。

## 4. 网络与数据契约

- 网络库只有 Retrofit + OkHttp + kotlinx.serialization。**禁止**再引入 xUtils、Volley、Fastjson、Gson、httpURLConnection 直连。
- DTO 只在 `:core:network`，以 `Dto` 结尾；领域模型只在 `:core:model`。
  两者之间必须有显式 `toDomain()` 映射，**不得让 DTO 直接进入 UI 层**。
- 后端契约固定：响应壳 `{code,msg,data}`，`code == 200` 为成功，其余归为 `AppError.Business`。
- 后端 `apikey`/`keytime` 实际不校验，但新客户端仍按契约携带，并在代码注释标注"后端不校验，勿作为安全机制"。
- base url 通过 `/icciu_api.php/` 前缀拼接，路径常量集中在一个文件，**禁止散落字符串字面量**。

## 5. 错误与状态

- 所有可能失败的调用返回 `AppResult<T>`，禁止向 UI 抛裸 `Exception`。
- UI 状态是不可变 data class，通过 `StateFlow<UiState>` 暴露。
- 一次性事件用 `Channel`/`SharedFlow`，不放进 `UiState`。
- 禁止 `System.exit`、`Runtime.getRuntime().exit`、静默吞异常（空 catch 必须有注释说明为何可忽略）。

## 6. 禁止清单（对照常见反模式）

| 禁止 | 原因 |
|---|---|
| 硬编码域名、密钥、签名口令、友盟/广告 SDK key | 域名与密钥全部内联在代码里，两端一致等于没有加密 |
| `usesCleartextTraffic=true`、`TrustAllCerts`、`handler.proceed()` | 中间人可改域名与配置 |
| WebView 开启 `addJavascriptInterface` + 文件访问 | 解析 WebView 全开（JS + 文件访问 + 混合内容） |
| 动态下发 base url 无校验 | 可被劫持到任意域名 |
| 第二套 UI 框架、第二套网络库、第二个播放器内核 | 曾见 4 套适配器 + 3 套网络 + 3 个播放内核并存 |
| 全局可变单例、静态 `var` | 配置文件式的一整排 static 字段 |
| `TODO()` / `NotImplementedError` 进主分支 | 编译能过、运行必崩 |
| 手写颜色/字号/间距 | 曾见 3457 处裸 dp、192 处裸色值 |

## 7. 测试要求

- `:core:model` 的规则函数必须有单元测试。
- `:core:network` 每个 DTO 必须有契约测试（MockWebServer + 真实响应 fixture），见 `docs/testing.md`。
- `:core:data` 用 fake remote 测仓库逻辑。
- ViewModel 用 `turbine` 测状态流。
- 新增解析规则（直链判定、试看判定、URL 归一化）必须带边界用例：空、null、非法格式。

## 8. 新增模块 / 新增页面的固定模板

新增 feature：

```
:feature:xxx
  build.gradle.kts        —— 只依赖 core 模块，不开新仓库
  src/main/kotlin/com/starveil/feature/xxx/
    XxxScreen.kt          —— 纯 UI，≤ 400 行
    XxxViewModel.kt       —— 状态 + 事件，≤ 200 行
    XxxUiState.kt         —— 不可变状态
    XxxNavigation.kt      —— 路由注册（供 :app 汇总）
  src/test/kotlin/...     —— ViewModel 测试
```

新增页面资源命名：`feature_xxx_` 前缀，避免与历史资源名 `item_card2` 这类重名撞车。

## 9. 列表页 / 详情页首帧快照（review 必查）

首帧走本地快照（stale-while-revalidate）：冷启动/进页面先铺上一次的内容，网络回来原地覆盖。
存储与策略集中在 `core:data` 的 `SnapshotStore` / `PageSnapshotStore`，**不要各页自己写文件**。
接入步骤与反例见 `PageSnapshotStore` 的类注释。review 时逐条对：

- [ ] 仓库成功分支写了快照，失败分支没写（失败不能覆盖上一次的好数据）
- [ ] 接口的 `cachedXxx()` 带默认实现（改完跑 `./gradlew check`，确认其它模块的 fake 没炸）
- [ ] ViewModel 初值走快照；刷新分支是 `isLoading = 当前没有内容`
- [ ] 首屏请求失败时保留已有内容，不跳整页错误态
- [ ] 快照里字段的语义变了 → `SnapshotStore.VERSION` 加一（旧文件自动失效，不写迁移代码）
- [ ] 涉及用户数据（收藏 / 历史 / 积分 / 提现）的页面：确认登录与登出会 `clearAll()`

首次访问没有快照时仍然要等网络，这是本地快照的固有边界：要连第一次都不转圈，
只能由后端下发预置数据或把内容打进 APK，客户端单方面做不到。
