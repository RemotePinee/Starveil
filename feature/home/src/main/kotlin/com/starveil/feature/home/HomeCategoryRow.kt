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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
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
) {
    val entries = remember(types) { listOf(VodType(id = 0, name = "首页")) + types }
    val scrollState = rememberScrollState()
    // 必须钉死高度：渐隐层用 fillMaxHeight，否则 Box 会被撑到占满剩余屏幕，正文列表高度变 0
    Box(modifier = modifier.height(CHIP_HEIGHT)) {
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
                    onClick = { onSelect(type.id) },
                )
            }
        }
        // 分类变多时右侧会溢出：用背景色渐变遮罩提示"还能滑"，滑到两端自动淡出/淡入
        CategoryEdgeFade(visible = scrollState.canScrollBackward, fromStart = true)
        CategoryEdgeFade(visible = scrollState.canScrollForward, fromStart = false)
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
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (selected) SELECTED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "category-chip-scale",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) {
            // 选中：画面级纯白。分类行压在首页顶部的模糊底色上，
            // 用 onSurface 在浅色主题下是深色字，会直接糊进底部颜色里。
            starveilExtraColors.onImage
        } else {
            // 未选中：浅灰（90% 阶梯灰，实色不透明），与选中的纯白拉开一档。
            starveilExtraColors.onImageSubtle
        },
        animationSpec = tween(Motion.MediumMs, easing = Motion.standard),
        label = "category-chip-text",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
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

private val CHIP_HEIGHT = Spacing.xxl + Spacing.lg

/** 选中放大倍数：优酷的量级，再大就挤到相邻项。 */
private const val SELECTED_SCALE = 1.16f

/** 渐隐宽度：约一个胶囊的高，够提示又不遮文字。 */
private val FADE_WIDTH = Spacing.xxl
