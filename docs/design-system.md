# 设计令牌与视觉规范

硬规则：**颜色、间距、圆角、字号、动效时长只允许来自本文件的令牌**。
颜色字面量与裸 dp 出现在令牌目录之外，会被 detekt 的 `starveil` 规则集
（`NoHardcodedColor` / `NoBareDp`，见 `tools/detekt-rules`）拦截，构建失败。

令牌目录：`core/designsystem/src/main/kotlin/com/starveil/core/designsystem/token/`

## 1. 两层颜色模型

同类实现的问题：219 个颜色、名字与值脱钩（`wheat` 的值是青色）、4 个不同的"主色"同时存在。
新模型分两层，职责不重叠：

### 1.1 Primitive（原色层，私有）

只描述"是什么颜色"，不允许业务直接使用。

```kotlin
internal object Palette {
    val Ink900 = Color(0xFF0B0F14)
    val Ink700 = Color(0xFF1C2530)
    …
    val Aqua500 = Color(0xFF25CEE8)
}
```

### 1.2 Semantic（语义层，唯一出口）

只描述"用在哪"，业务与页面只能用这一层。

| 语义 | 用途 | 对应 Material |
|---|---|---|
| `primary` | 主操作、强调 | `colorScheme.primary` |
| `onPrimary` | 主色上的文字/图标 | `colorScheme.onPrimary` |
| `surface` / `surfaceVariant` | 页面底、卡片 | `colorScheme.surface*` |
| `onSurface` / `onSurfaceVariant` | 正文/辅助文字 | `colorScheme.onSurface*` |
| `outline` / `outlineVariant` | 描边、分隔线 | `colorScheme.outline*` |
| `error` / `onError` | 错误态 | `colorScheme.error*` |
| `scrim` | 遮罩 | `colorScheme.scrim` |

页面写法：

```kotlin
// 正确
Text(color = MaterialTheme.colorScheme.onSurfaceVariant)

// 错误，构建失败
Text(color = Color(0xFF999999))
Text(color = Color.parseColor("#999999"))
```

需要 Material 语义表达不了的品牌色（如会员金色角标）时，在 `StarveilTheme.colors.brandGold`
这类**扩展语义**里加，仍然走令牌。

## 2. 间距

4dp 基准栅格，禁止任意值。

| 令牌 | 值 | 典型用途 |
|---|---|---|
| `Spacing.xxs` | 2.dp | 图标与文字微调 |
| `Spacing.xs` | 4.dp | 标签内边距 |
| `Spacing.sm` | 8.dp | 元素间距 |
| `Spacing.md` | 12.dp | 卡片内间距 |
| `Spacing.lg` | 16.dp | 页面边距（默认） |
| `Spacing.xl` | 24.dp | 区块间距 |
| `Spacing.xxl` | 32.dp | 页面大分隔 |
| `Spacing.rowHeight` | 52.dp | 列表行高（图标 + 文案 + 箭头） |
| `Spacing.control` | 56.dp | 表单控件高度（输入框 / 主按钮） |
| `Spacing.tabBarHeightIcons` / `tabBarHeightText` | 64 / 44.dp | 底栏两种模式的高度 |
| `Spacing.splashAnimation` / `splashLogoWidth` / `splashLogoHeight` | 168 / 160 / 69.dp | 开屏动画与底部 lockup（与 `splash_window.xml` 对齐） |
| `Spacing.searchBarExpandedWidth` | 208.dp | 剧场页搜索胶囊展开宽度 |
| `Spacing.livePickerGroupWidth` | 96.dp | 全屏选台面板左栏宽度 |
| `Spacing.danmuLineHeight` / `danmuLineGap` | 24 / 6.dp | 弹幕行高与行间距 |
| `Spacing.none` / `hairline` | 0 / 1.dp | 零间距 / 1dp 描边 |

页面级横向边距统一 `Spacing.lg`，列表项纵向 `Spacing.md`。

## 3. 圆角与形状

| 令牌 | 值 | 用途 |
|---|---|---|
| `Shapes.extraSmall` | 4.dp | 标签、徽章 |
| `Shapes.small` | 8.dp | 输入框、小按钮 |
| `Shapes.medium` | 12.dp | 卡片（默认） |
| `Shapes.large` | 16.dp | 大卡片、底部弹层 |
| `Shapes.extraLarge` | 24.dp | 全屏弹层 |

海报图统一 `medium`，不允许页面自行写 `RoundedCornerShape(10.dp)`。

## 4. 字体

使用 Material 3 `Typography`，**不新增散值字号**。

| 语义 | 令牌 | 用途 |
|---|---|---|
| 大标题 | `headlineSmall` | 页面主标题 |
| 区块标题 | `titleMedium` | 分组标题 |
| 正文 | `bodyMedium` | 主要内容 |
| 辅助 | `bodySmall` | 次要信息、时间 |
| 标签 | `labelMedium` | 按钮、Tab |

禁止在布局里写 `fontSize = 14.sp`；需要强调时用 `fontWeight` 而非换字号。

## 5. 动效

| 令牌 | 值 | 用途 |
|---|---|---|
| `Motion.short` | 120 ms | 按下反馈、淡入 |
| `Motion.medium` | 200 ms | 展开/收起、切页 |
| `Motion.long` | 320 ms | 全屏转场 |

缓动统一 `Easing.standard`（`FastOutSlowIn`）。禁止页面自定 `tween(700)` 这类长时长。

## 6. 图标

- 尺寸只用 `IconSize.sm = 16.dp`、`IconSize.item = 20.dp`（列表行）、`IconSize.tab = 22.dp`（底栏）、
  `md = 24.dp`、`lg = 32.dp`、`xl = 36.dp`。
- 图标资源放 `core/designsystem/src/main/res/drawable/`，命名 `ic_starveil_<name>`。
- 禁止拉伸位图当图标。

## 7. 深色模式

首期只做浅色，但令牌层必须按"可扩展"写：

- 颜色一律通过 `ColorScheme` 提供，不在组件里判断 `isSystemInDarkTheme()`。
- 将来加 `values-night` / 深色 `ColorScheme` 时，页面代码零改动。

## 8. 新增令牌流程

1. 先判断现有语义能否表达；能则不加。
2. 不能则加到 `Palette`（若为新原色）与 `StarveilColors`（若为品牌语义）。
3. 同步更新本文件表格。
4. 不允许"一次性"令牌（只在单个页面用一次的颜色/尺寸）。


## 7. 视觉方向（2026-10 改版）

首页按商业影视 App 的结构重做，视觉参照：深色底、海报主视觉、标题压渐变、
频道 Tab 加粗下划线、快捷入口胶囊、编辑精选双列、TOP 角标榜单、五栏底部导航。

| 项 | 规则 |
|---|---|
| 主形态 | **暗色**（`Palette.Void` 背景 / `Ink950` 表面 / `Ink850` 卡片） |
| 皮肤 | 只换强调色与角标色；中性骨架固定，页面不写 `if (skin)` |
| 标题 | 海报叠字用 `displaySmall`，区块标题 `headlineSmall`，卡片标题 `titleMedium` |
| 渐变 | 海报叠字统一用 `starveilExtraColors.heroScrimStart/End`，不在页面里写颜色 |
| 角标 | TOP / 独播 / 活动统一 `Badge` 组件 + `starveilExtraColors.badge` |
| 圆角 | 海报 `AppShape.small/medium`，轮播大卡 `AppShape.large` |

参考实现：`feature/home/.../HomeTopBar.kt`、`HomeTabs.kt`、`HomeHero.kt`、`HomeSections.kt`。
