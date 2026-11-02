package com.starveil.feature.shortplay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ShortPlayType
import kotlin.math.floor

/**
 * 顶部横滑分类条：字号、字重、颜色档位、两端渐隐与首页的分类行逐项对齐。
 *
 * 实现照抄 `:feature:home` 的 `CategoryChips` 而不是直接引用它——AGENTS.md 禁止
 * `:feature:A` 依赖 `:feature:B`；迁进 `:core:designsystem` 又会把两页的视觉耦死，
 * 复制一份让剧场页可以单独演进。
 *
 * 选中态与首页那条不同：这里**不放大**（放大后文字重排，会把左右相邻项一起挤走），
 * 改成文字下方一条会滑过去的下划线。剧场页内容整体居中，一屏放不下时两头各露半截；
 * 分类页那两条把下划线关掉（[showIndicator] = false），两级层级改用字号差表达。
 */
@Composable
internal fun ShortPlayCategoryRow(
    categories: List<ShortPlayType>,
    selectedTypeId: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 两侧留白：剧场页居中，左右必须对称，否则居中的落点会偏；分类页左对齐，它就是页面左边距。 */
    horizontalPadding: Dp = Spacing.xxs,
    /** 文字配色档位：默认「压画面」的白字，实底色页面必须显式传 [ShortPlayCategoryTone.OnSurface]。 */
    tone: ShortPlayCategoryTone = ShortPlayCategoryTone.OnImage,
    /**
     * 连续页位置（`pager.currentPage + pager.currentPageOffsetFraction`），由外层横向 pager 给。
     *
     * 非空时下划线按这个值逐帧插值，跟着手指在两页之间连续移动；为空则退回
     * 「点谁补间到谁」——分类页那两条纯点击的分类条走这条路。
     */
    progress: (() -> Float)? = null,
    /**
     * 是否画选中下划线。
     *
     * 下划线是剧场页的"当前页"指示器，跟着 pager 连续滑动；分类页两级只靠点击联动，
     * 层级改用字号差表达，再压两条线只会糊成一片，故传 `false`。
     */
    showIndicator: Boolean = true,
    /** 行高档位：剧场页保持默认，分类页一级 / 二级各取一档（见 [ShortPlayCategoryRowLayout]）。 */
    layout: ShortPlayCategoryRowLayout = ShortPlayCategoryRowLayout.Theater,
    /**
     * 整条分类条怎么摆：
     *
     * - `true`（剧场页）：内容居中——不足一屏时整排居中，超出一屏时首帧滚到正中，两头各露半截；
     * - `false`（分类页）：内容左对齐，与下面的海报网格同一条左边距；首帧滚到当前选中项。
     */
    centered: Boolean = true,
) {
    val scrollState = rememberScrollState()
    // 每个分类文字在滚动内容里的横向范围。下划线要跨项滑动，只能由分类条统一画：
    // 留在每一项里就只能是"这里淡出、那里淡入"，看不到"滑过去"。
    val spans = remember { mutableStateMapOf<Int, ChipSpan>() }
    val selectedSpan = spans[selectedTypeId]
    // 边缘淡出用 BlendMode.DstOut 把内容本身擦成透明，而不是盖一层背景色：
    // 这一行在剧场页压在短剧封面上，底色随时在变，任何纯色蒙版都会露出一块斑。
    // 左侧只在能往回滚时才出现：剧场页居中起步两头各露半截，分类页从选中项起步。
    val edgePx = with(LocalDensity.current) { EDGE_FADE.toPx() }
    val fadeStart by animateFloatAsState(
        targetValue = if (scrollState.canScrollBackward) 1f else 0f,
        animationSpec = tween(Motion.MediumMs),
        label = "shortplay-chip-fade-start",
    )
    val fadeEnd by animateFloatAsState(
        targetValue = if (scrollState.canScrollForward) 1f else 0f,
        animationSpec = tween(Motion.MediumMs),
        label = "shortplay-chip-fade-end",
    )
    // DstOut 只吃 alpha 通道，颜色本身不参与画面；取令牌里的遮罩色，别再写字面量
    val fadeMask = starveilExtraColors.fadeMask
    val underlineColor = selectedMarkColor(tone)
    BoxWithConstraints(
        modifier = modifier
            .height(layout.height)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (fadeStart > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(fadeMask.copy(alpha = fadeStart), Color.Transparent),
                            startX = 0f,
                            endX = edgePx,
                        ),
                        size = Size(edgePx, size.height),
                        blendMode = BlendMode.DstOut,
                    )
                }
                if (fadeEnd > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, fadeMask.copy(alpha = fadeEnd)),
                            startX = size.width - edgePx,
                            endX = size.width,
                        ),
                        topLeft = Offset(size.width - edgePx, 0f),
                        size = Size(edgePx, size.height),
                        blendMode = BlendMode.DstOut,
                    )
                }
            },
    ) {
        // 内容不足一屏时由 `minWidth` 把 Row 撑到整行宽度，Arrangement.Center 才有空隙可分；
        // 超出一屏时它就是一整条可滚内容，居中的效果落在初始滚动位置上（见 ShortPlayCategoryRowScroll）。
        val viewportWidth = maxWidth
        ShortPlayCategoryRowScroll(
            scrollState = scrollState,
            contentKey = categories,
            centered = centered,
            selectedSpan = selectedSpan,
            leadingInset = horizontalPadding,
        )
        Box(modifier = Modifier.horizontalScroll(scrollState)) {
            // 与首页一样用 Row + horizontalScroll：分类是后台配的个位数列表，不需要懒加载
            Row(
                modifier = Modifier
                    .widthIn(min = viewportWidth)
                    .padding(horizontal = horizontalPadding),
                horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                categories.forEach { type ->
                    ShortPlayCategoryChip(
                        label = type.name.ifBlank { "全部" },
                        selected = type.id == selectedTypeId,
                        enabled = enabled,
                        onClick = { onSelect(type.id) },
                        modifier = Modifier.reportChipSpan(type.id) { id, span -> spans[id] = span },
                        tone = tone,
                        layout = layout,
                    )
                }
            }
            // 覆盖层只画不占位（matchParentSize），下划线不参与可滚内容的宽度。
            // 分类页两级都不画线：层级靠字号差，两条线叠在一起只会糊成一片。
            if (showIndicator) {
                ShortPlayUnderlineOverlay(
                    categories = categories,
                    spans = spans,
                    selectedSpan = selectedSpan,
                    progress = progress,
                    color = underlineColor,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/** 下划线覆盖层：`matchParentSize` 只画不占位，撑不大分类条的可滚范围。 */
@Composable
private fun ShortPlayUnderlineOverlay(
    categories: List<ShortPlayType>,
    spans: Map<Int, ChipSpan>,
    selectedSpan: ChipSpan?,
    color: Color,
    /** 连续页位置：非空时跟手指滑动，为空按选中项补间。 */
    progress: (() -> Float)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.BottomStart) {
        // 有连续进度就跟手指（剧场页横滑切分类），没有才按选中项补间（分类页纯点击）。
        if (progress == null) {
            ShortPlayUnderline(span = selectedSpan, color = color)
        } else {
            ShortPlayContinuousUnderline(categories, spans, progress, color)
        }
    }
}

/**
 * 把分类项在滚动内容坐标系里的位置回传给分类条（下划线按它定位）。
 *
 * 用 `boundsInParent` 而不是窗口坐标：父节点就是 Row，滚动是整条内容一起位移，
 * 子项在 Row 坐标系里的位置不随滚动改变，所以这里不用每帧减去滚动量。
 */
@Composable
private fun Modifier.reportChipSpan(typeId: Int, onSpan: (Int, ChipSpan) -> Unit): Modifier {
    val density = LocalDensity.current
    val sideInset = with(density) { Spacing.xs.toPx() }
    return onGloballyPositioned { coords ->
        val bounds = coords.boundsInParent()
        onSpan(
            typeId,
            ChipSpan(
                // 下划线画在文字底下而不是整块点击区：左右各让出这一格内边距
                left = with(density) { (bounds.left + sideInset).toDp() },
                width = with(density) { (bounds.width - sideInset * 2).toDp() },
            ),
        )
    }
}

/** 一个分类文字在滚动内容坐标系里的横向范围。 */
internal data class ChipSpan(val left: Dp, val width: Dp)

/**
 * 分类文字范围 → 下划线自身的位置：宽度固定，左边界按这条线的宽度在文字上居中。
 *
 * 文字比线还窄时夹回文字左缘，宁可右边多出一截，也不让线滑到相邻分类底下。
 */
private fun ChipSpan.underlineSpan(width: Dp = UNDERLINE_WIDTH): ChipSpan =
    ChipSpan(left = (left + (this.width - width) / 2).coerceAtLeast(left), width = width)

/**
 * 选中项下方那条下划线（点击驱动）：宽度恒为 [UNDERLINE_WIDTH]，只有位置按 [ChipSpan] 补间，
 * 分类名再长线也不跟着变长。
 *
 * 抽成独立组件有两个用处：动效只在它自己身上重组，不带着整排分类一起刷；
 * 首帧落位走 `snap`——那段从最左边滑到中间项的 200ms 只是初始化，不是给用户看的动效。
 */
@Composable
private fun ShortPlayUnderline(span: ChipSpan?, color: Color) {
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(span) {
        if (span != null) placed = true
    }
    val spec: AnimationSpec<Dp> =
        if (placed) tween(Motion.MediumMs, easing = Motion.standard) else snap()
    val left by animateDpAsState(
        targetValue = span?.underlineSpan()?.left ?: Spacing.none,
        animationSpec = spec,
        label = "shortplay-chip-underline-left",
    )
    ShortPlayUnderlineBar(left = left, width = UNDERLINE_WIDTH, color = color)
}

/**
 * 横滑驱动的那一条：只有位置按 pager 的连续位置在两页之间插值，宽度固定，跟着手指走。
 *
 * `progress()` 的读取刻意放在这个函数体内：它每帧都会变，作用域收在这里，
 * 逐帧失效的只有这条下划线，整排分类文字与滚动位置都不跟着重组。
 */
@Composable
private fun ShortPlayContinuousUnderline(
    categories: List<ShortPlayType>,
    spans: Map<Int, ChipSpan>,
    progress: () -> Float,
    color: Color,
) {
    val span = interpolateChipSpan(categories, spans, progress())
    ShortPlayUnderlineBar(
        left = span?.left ?: Spacing.none,
        width = span?.width ?: Spacing.none,
        color = color,
    )
}

/** 两种驱动（点击补间 / 横滑跟手）最终都落在这一个节点上，样式只写一份。 */
@Composable
private fun ShortPlayUnderlineBar(left: Dp, width: Dp, color: Color) {
    Box(
        modifier = Modifier
            .padding(bottom = UNDERLINE_INSET)
            .offset(x = left)
            .width(width)
            .height(UNDERLINE_HEIGHT)
            .background(color = color, shape = AppShape.full),
    )
}

/**
 * 连续页位置 → 当前下划线位置：落点页与下一页各自「居中后的左边界」按小数部分插值，
 * 宽度恒为 [UNDERLINE_WIDTH]——横滑时是一条固定长度的线在移动，不是一条会伸缩的橡皮筋。
 *
 * 位置先夹进 `[0, lastIndex]`：pager 两端允许过滚，过滚不该把下划线推出分类条。
 */
internal fun interpolateChipSpan(
    categories: List<ShortPlayType>,
    spans: Map<Int, ChipSpan>,
    position: Float,
): ChipSpan? {
    if (categories.isEmpty()) return null
    val lastIndex = categories.lastIndex
    val clamped = position.coerceIn(0f, lastIndex.toFloat())
    val base = floor(clamped).toInt().coerceIn(0, lastIndex)
    val next = (base + 1).coerceAtMost(lastIndex)
    val fraction = clamped - base
    val from = spans[categories[base].id]?.underlineSpan()
    val to = spans[categories[next].id]?.underlineSpan()
    return when {
        // 某一段跨度还没回传（首帧布局）时退回另一段，避免闪成一条零宽线。
        from == null -> to
        to == null -> from
        else -> ChipSpan(left = lerp(from.left, to.left, fraction), width = UNDERLINE_WIDTH)
    }
}

/**
 * 分类项：只有文字，选中态的下划线由 [ShortPlayCategoryRow] 统一画。
 *
 * 切换时字重加粗、颜色抬亮（选中更实、未选中更透），不做缩放。
 * 点击不带水波纹——这里不是按钮，留个方框很难看。
 */
@Composable
private fun ShortPlayCategoryChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ShortPlayCategoryTone = ShortPlayCategoryTone.OnImage,
    /** 行高档位：与所在分类条同一档，字号也跟着它选。 */
    layout: ShortPlayCategoryRowLayout = ShortPlayCategoryRowLayout.Theater,
) {
    // 选中/未选中各取当前底色的一档，不在这里写死颜色
    val textColor by animateColorAsState(
        targetValue = if (selected) selectedMarkColor(tone) else unselectedMarkColor(tone),
        animationSpec = tween(Motion.MediumMs, easing = Motion.standard),
        label = "shortplay-chip-text",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(layout.height)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = "切换分类",
                onClick = onClick,
            )
            .padding(horizontal = Spacing.xs)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = chipTextStyle(layout.secondaryText),
            // 选中靠更重的字重（ExtraBold）把笔画压粗；颜色档位由 tone 决定，这里只管粗细。
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
        )
    }
}

/** 二级分类降一档字阶：两级都堆在一起时，字号差是最省地方的分级信号。 */
@Composable
private fun chipTextStyle(secondaryText: Boolean): TextStyle =
    if (secondaryText) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge

/** 选中态的文字与下划线同色：两处是同一个「当前项」信号，差一档会显脏。 */
@Composable
private fun selectedMarkColor(tone: ShortPlayCategoryTone): Color = when (tone) {
    ShortPlayCategoryTone.OnImage -> starveilExtraColors.onImage
    ShortPlayCategoryTone.OnSurface -> MaterialTheme.colorScheme.onBackground
}

/**
 * 未选中文字：比选中态再淡一档。
 *
 * 压画面时取白系的 `onImageFaint`（固定浅灰，浅色主题下也不会变成深色糊进封面）；
 * 实底色页面取三级文字 `textFaint`，深浅主题里都对应「比次级再淡一档」的那一档。
 */
@Composable
private fun unselectedMarkColor(tone: ShortPlayCategoryTone): Color = when (tone) {
    ShortPlayCategoryTone.OnImage -> starveilExtraColors.onImageFaint
    ShortPlayCategoryTone.OnSurface -> starveilExtraColors.textFaint
}

/** 下划线粗细：比 1dp 描边厚一档，1dp 在高密度屏上几乎看不见。 */
private val UNDERLINE_HEIGHT = Spacing.hairline * 2

/**
 * 下划线宽度：固定 20dp，**不跟随分类文字的宽度**——分类名再长，线也不跟着变长。
 *
 * 取 `Spacing.md + Spacing.sm`，比最短的分类名还短一截，读作「当前项的标记」。
 */
internal val UNDERLINE_WIDTH = Spacing.md + Spacing.sm

/** 下划线离分类条底边的距离：托在文字下方，不贴着底。 */
private val UNDERLINE_INSET = Spacing.xs

/**
 * 分类行两端内容淡出的宽度。
 *
 * 这里只决定「擦掉多宽」，不涉及任何颜色：淡出由 BlendMode.DstOut 把已画的内容
 * 按 alpha 擦成透明，透出来的是底下的底色（封面或页面底色），浅色深色都不会冒出白块黑块。
 */
private val EDGE_FADE = Spacing.xl
