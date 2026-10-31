package com.starveil.feature.shortplay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ShortPlayType

/**
 * 顶部横滑分类条：手感与首页的分类行逐项对齐（字号、字重、颜色档位、点按缩放、两端渐隐）。
 *
 * 实现照抄 `:feature:home` 的 `CategoryChips` 而不是直接引用它——AGENTS.md 禁止
 * `:feature:A` 依赖 `:feature:B`；迁进 `:core:designsystem` 又会把两页的视觉耦死，
 * 复制一份让剧场页可以单独演进。
 */
@Composable
internal fun ShortPlayCategoryRow(
    categories: List<ShortPlayType>,
    selectedTypeId: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 首个分类的起始留白；与图标并排时留窄一点，别把可滚宽度吃掉。 */
    leadingPadding: Dp = Spacing.xxs,
) {
    val scrollState = rememberScrollState()
    // 边缘淡出用 BlendMode.DstOut 把内容本身擦成透明，而不是盖一层背景色：
    // 这一行压在短剧封面上，底图颜色随时在变，任何纯色蒙版都会露出一块斑。
    // 左侧只在能往回滚时才出现，静止时第一个分类是完整的。
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
    Box(
        modifier = modifier
            .height(CHIP_HEIGHT)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (fadeStart > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Black.copy(alpha = fadeStart), Color.Transparent),
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
                            listOf(Color.Transparent, Color.Black.copy(alpha = fadeEnd)),
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
        // 与首页一样用 Row + horizontalScroll：分类是后台配的个位数列表，不需要懒加载，
        // 内容多宽就多宽，剩下的宽度留给搜索图标。
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(start = leadingPadding, end = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            categories.forEach { type ->
                ShortPlayCategoryChip(
                    label = type.name.ifBlank { "全部" },
                    selected = type.id == selectedTypeId,
                    enabled = enabled,
                    onClick = { onSelect(type.id) },
                )
            }
        }
    }
}

/**
 * 分类项：只有文字，没有背景块、没有下划线。
 *
 * 切换时照首页那套手感走：选中项弹一下放大（带一点过冲）、字重加粗、颜色抬亮，
 * 未选中项缩回原尺寸并压暗。点击不带水波纹——这里不是按钮，留个方框很难看。
 */
@Composable
private fun ShortPlayCategoryChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) SELECTED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "shortplay-chip-scale",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) {
            // 选中：画面级纯白。分类条压在封面上，用 onSurface 在浅色主题下是深色字，
            // 会直接糊进底图颜色里。
            starveilExtraColors.onImage
        } else {
            // 未选中：浅灰（90% 阶梯灰，实色不透明），与选中的纯白拉开一档。
            starveilExtraColors.onImageSubtle
        },
        animationSpec = tween(Motion.MediumMs, easing = Motion.standard),
        label = "shortplay-chip-text",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(CHIP_HEIGHT)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = "切换分类",
                onClick = onClick,
            )
            .padding(horizontal = Spacing.xs)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            // 两个档位都是纯白，选中靠更重的字重（ExtraBold）把笔画压粗，视觉上更白更实。
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
        )
    }
}

private val CHIP_HEIGHT = Spacing.xxl + Spacing.lg

/** 选中放大倍数：与首页一致，再大就挤到相邻项。 */
private const val SELECTED_SCALE = 1.16f

/**
 * 分类行两端内容淡出的宽度。
 *
 * 这里只决定「擦掉多宽」，不涉及任何颜色：淡出由 BlendMode.DstOut 把已画的内容
 * 按 alpha 擦成透明，透出来的是底下封面本身的颜色，浅色深色都不会冒出白块黑块。
 */
private val EDGE_FADE = Spacing.xl
