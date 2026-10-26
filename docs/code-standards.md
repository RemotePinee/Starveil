# 代码规范

规则来源两层：**机器校验**（违反即构建失败）与**评审要求**（人工把关）。
机器校验部分见 `AGENTS.md` 第 2、3 节与根 `build.gradle.kts` 里的三个 `verify*` 任务。

## 1. 命名

| 对象 | 规则 | 示例 |
|---|---|---|
| 包 | 全小写，`com.starveil.<module>[.<feature>]` | `com.starveil.core.network` |
| 类 | PascalCase，名词 | `VodRepository` |
| 接口 | PascalCase，不加 `I` 前缀 | `VodRepository` |
| 实现类 | 接口名 + 后缀 | `DefaultVodRepository` |
| DTO | PascalCase + `Dto` | `VodDto` |
| 领域模型 | PascalCase，无后缀 | `Vod` |
| Composable | PascalCase，名词短语 | `VodCard`、`HomeScreen` |
| 状态 | PascalCase + `UiState` | `HomeUiState` |
| 事件 | PascalCase + `Event`/`Action` | `HomeAction` |
| 常量 | UPPER_SNAKE | `DEFAULT_PAGE_SIZE` |
| 资源文件 | snake_case，`feature_` 前缀 | `feature_home_poster.xml` |

禁止的命名：`Manager`、`Helper`、`Util`、`Utils`、`Data`、`Info`、`Bean`、`Common`。
这些词不描述职责（`SimpleUtils`、`AUtils`、`PlayScoreBean` 这类命名全属此类）。
确需工具类时用具体动词命名，例如 `UrlNormalizer`、`TimeFormatter`。

## 2. 文件结构

- 一个文件一个顶层 public 声明（Compose 的多个小 Composable 可同文件，但总数 ≤ 5）。
- 顺序：文件头注释（版权可选）→ package → import → 声明。
- `import` 不允许通配符。
- 扩展函数放在目标类型的同包下，命名 `XxxExt.kt`。

## 3. 函数

- 单一职责，可被一句话描述。
- 早返回优先于嵌套 `if`。
- 超过 3 个同类参数时用具名参数调用。
- 布尔参数禁止裸传（`setVisible(true)` 之类），改用枚举或具名参数。

## 4. Compose

- Composable 只做渲染与事件上抛，不做业务计算、不直接访问 Repository。
- 参数顺序：`modifier: Modifier = Modifier` 放第一个可选参数位；回调 `onXxx: () -> Unit` 放最后。
- 状态用 `collectAsStateWithLifecycle()`，不用 `collectAsState()`。
- 列表项必须给稳定 `key`。
- 预览函数命名 `XxxPreview`，加 `@Preview`，不引用 ViewModel。
- 禁止在 Composable 里创建 `ViewModel`（应通过参数或工厂传入），避免预览崩溃。
- 禁止在 Composable 里写 `LaunchedEffect(Unit) { }` 做一次性初始化，改用 `rememberSaveable` + 显式事件。

## 5. 协程

- 禁止 `GlobalScope`、`runBlocking`（测试除外）。
- `suspend` 函数必须主入口安全，内部用 `withContext` 切线程。
- 禁止在 `Dispatchers.Main` 上做 IO。
- `Flow` 用 `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`。

## 6. 日志

- 统一封装 `StarveilLogger`，禁止裸 `Log.d`。
- 禁止打印 Cookie、Authorization、手机号、用户 ID、播放地址。
- Release 构建关闭 DEBUG 日志（用 `BuildConfig` 或注入开关）。

## 7. 注释

- 解释**为什么**，不解释**是什么**。
- 与后端契约相关的坑必须写注释，说清参数语义与误用后果，例如：

```kotlin
// 后端 /v1.vod 同时承担 banner、榜单、列表、搜索四种语义，
// 只靠参数区分；任何参数写错都会静默返回全量列表。
```

- 禁留 `// TODO`、`// FIXME` 进主分支（detekt 可配置为 failure）。

## 8. 提交前自检清单

- [ ] `./gradlew check` 全绿
- [ ] 新增文件 ≤ 400 行，新增类 ≤ 200 行，新增函数 ≤ 40 行
- [ ] 没有颜色字面量（`Color(0x...)`、`#hex`）
- [ ] 没有硬编码域名、密钥、手机号
- [ ] DTO 没有泄漏到 UI 层
- [ ] 新增解析/判定逻辑带了边界测试
- [ ] 新增依赖已登记到 `libs.versions.toml`，且 `AGENTS.md` 的禁止清单未违反
