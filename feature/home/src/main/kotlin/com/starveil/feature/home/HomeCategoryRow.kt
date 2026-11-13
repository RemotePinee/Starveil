package com.starveil.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.VodType
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

/**
 * 分类行：搜索栏下方那一排胶囊。
 *
 * 首项固定"首页"（typeId=0，表示不过滤），其后是后端一级分类——顶部不再单独渲染
 * 频道 Tab，分类全页只出现这一次。胶囊沿用原快捷入口的尺寸（[CHIP_HEIGHT] / AppShape.small /
 * chipBackground），选中项换成主题的 primaryContainer 底 + primary 文字，不新造配色。
 */
@Composable
fun CategoryChips(
    types: List<VodType>,
    selectedTypeId: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 首尾留白；并排在搜索框右边时收窄，避免和搜索框之间空一条。 */
    leadingPadding: Dp = Spacing.lg,
    /** 0 = 压在轮播图上（白字），1 = 落在页面底色上（深色字）。 */
    coverProgress: Float = 0f,
) {
    val entries = remember(types) { listOf(VodType(id = 0, name = "首页")) + types }
    val scrollState = rememberScrollState()
    // 边缘淡出用 BlendMode.DstOut 把内容本身擦成透明，而不是盖一层背景色：
    // 这一行叠在轮播图上，背景是随图变化的颜色，任何纯色蒙版都会露出一块斑。
    // 左侧只在能往回滚时才出现，静止时第一个分类是完整的。
    val edgePx = with(LocalDensity.current) { EDGE_FADE.toPx() }
    // 页面滑到屏幕外的分类时，把选中项**最小幅度**滚进视口：
    // 左侧被挡就从左边缘滑进来，右侧超出就只推到刚好露完；已在视口内则不动。
    // 不做"总是居中"——那会让一次切页直接横跳。
    val chipStart = remember { mutableStateMapOf<Int, Int>() }
    val chipWidth = remember { mutableStateMapOf<Int, Int>() }
    var viewportWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedTypeId, viewportWidth) {
        anchorTarget(
            AnchorInput(
                start = chipStart[selectedTypeId] ?: -1,
                width = chipWidth[selectedTypeId] ?: 0,
                viewport = viewportWidth,
                scrollValue = scrollState.value,
                edge = edgePx.roundToInt(),
            ),
        )?.let { scrollState.animateScrollTo(it.coerceIn(0, scrollState.maxValue)) }
    }
    val fadeStart by animateFloatAsState(
        targetValue = if (scrollState.canScrollBackward) 1f else 0f,
        animationSpec = tween(Motion.MediumMs),
        label = "chip-fade-start",
    )
    val fadeEnd by animateFloatAsState(
        targetValue = if (scrollState.canScrollForward) 1f else 0f,
        animationSpec = tween(Motion.MediumMs),
        label = "chip-fade-end",
    )
    // DstOut 只吃 alpha 通道，颜色本身不参与画面；取令牌里的遮罩色，别再写字面量
    val fadeMask = starveilExtraColors.fadeMask
    Box(
        modifier = modifier
            .onSizeChanged { viewportWidth = it.width }
            .height(CHIP_HEIGHT)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .edgeFades(fadeStart = fadeStart, fadeEnd = fadeEnd, edgePx = edgePx, mask = fadeMask)
    ) {
        // 用普通 Row + horizontalScroll 而不是 LazyRow：LazyRow 的视口必定填满约束，
        // 宽度无法「内容多宽就多宽」，调用方就没法把剩下的宽度让给搜索框。
        // 分类是后台配置的个位数列表，也不需要懒加载。
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(start = leadingPadding, end = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEach { type ->
                CategoryChip(
                    label = type.name,
                    selected = type.id == selectedTypeId,
                    coverProgress = coverProgress,
                    onClick = { onSelect(type.id) },
                    modifier = Modifier.onGloballyPositioned { coords ->
                        chipStart[type.id] = coords.positionInParent().x.roundToInt()
                        chipWidth[type.id] = coords.size.width
                    },
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.CategoryEdgeFade(visible: Boolean, fromStart: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(Motion.MediumMs),
        label = "category-edge-fade",
    )
    val background = MaterialTheme.colorScheme.background
    val brush = if (fromStart) {
        Brush.horizontalGradient(listOf(background, background.copy(alpha = 0f)))
    } else {
        Brush.horizontalGradient(listOf(background.copy(alpha = 0f), background))
    }
    Box(
        modifier = Modifier
            .align(if (fromStart) Alignment.CenterStart else Alignment.CenterEnd)
            .fillMaxHeight()
            .width(FADE_WIDTH)
            .graphicsLayer { this.alpha = alpha }
            .background(brush),
    )
}

/**
 * 分类项：只有文字，没有背景块、没有下划线。
 *
 * 切换时照优酷那套手感走：选中项弹一下放大（带一点过冲）、字重加粗、颜色抬亮，
 * 未选中项缩回原尺寸并压暗。点击不带水波纹——这里不是按钮，留个方框很难看。
 */
@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    coverProgress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) SELECTED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "category-chip-scale",
    )
    // 压在剧照模糊上是白系；轮播滚走、底下变成页面底色后换成正文色。
    // 两套按 coverProgress 插值：浅色模式下不会白字压浅底，深色模式两头都是浅色，看不出变化。
    val textColor by animateColorAsState(
        targetValue = if (selected) {
            lerp(starveilExtraColors.onImage, MaterialTheme.colorScheme.onBackground, coverProgress)
        } else {
            lerp(starveilExtraColors.onImageFaint, starveilExtraColors.textMuted, coverProgress)
        },
        animationSpec = tween(Motion.MediumMs),
        label = "category-chip-text",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(CHIP_HEIGHT)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.xs)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            // 颜色两者都是纯白，选中靠更重的字重（ExtraBold）把笔画压粗，视觉上更白更实。
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
        )
    }
}

/**
 * 计算"让选中项刚好露出来"的目标滚动值；已在视口内返回 null。
 *
 * 纯函数：不读 Compose 状态，方便把圈复杂度从 `CategoryChips` 里分离出去。
 */
private fun anchorTarget(input: AnchorInput): Int? = when {
    input.start < 0 || input.viewport <= 0 -> null
    input.start < input.scrollValue -> input.start - input.edge
    input.start + input.width > input.scrollValue + input.viewport ->
        input.start + input.width - input.viewport + input.edge

    else -> null
}


/** 两端渐隐：用 BlendMode.DstOut 把已画内容擦成透明，透出底下轮播图本身的颜色。 */
private fun Modifier.edgeFades(fadeStart: Float, fadeEnd: Float, edgePx: Float, mask: Color): Modifier =
    drawWithContent {
        drawContent()
        if (fadeStart > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(mask.copy(alpha = fadeStart), Color.Transparent),
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
                    listOf(Color.Transparent, mask.copy(alpha = fadeEnd)),
                    startX = size.width - edgePx,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - edgePx, 0f),
                size = Size(edgePx, size.height),
                blendMode = BlendMode.DstOut,
            )
        }
    }
/** 锚点计算入参：字段多，用持有者避免函数签名超 detekt 的 5 个参数。 */
private data class AnchorInput(
    val start: Int,
    val width: Int,
    val viewport: Int,
    val scrollValue: Int,
    val edge: Int,
)

/** 分类行高度：顶栏高度与列表让位都按它算，所以对模块内可见。 */
internal val CHIP_HEIGHT = Spacing.xxl + Spacing.lg

/** 选中放大倍数：优酷的量级，再大就挤到相邻项。 */
private const val SELECTED_SCALE = 1.16f

/** 渐隐宽度：约一个胶囊的高，够提示又不遮文字。 */
private val FADE_WIDTH = Spacing.xxl

/**
 * 分类行两端内容淡出的宽度。
 *
 * 这里只决定"擦掉多宽"，不涉及任何颜色：淡出由 BlendMode.DstOut 把已画的内容
 * 按 alpha 擦成透明，透出来的是底下轮播图本身的颜色，浅色深色都不会冒出白块黑块。
 */
private val EDGE_FADE = Spacing.xl
