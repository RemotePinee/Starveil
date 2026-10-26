package com.starveil.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing

/**
 * 一行筛选项。
 */
@Composable
internal fun FilterChipRow(row: FilterRow, onSelect: (FilterKind, String) -> Unit) {
    // 不摆左侧维度名：选项自己就说明了是什么，省下的宽度全给选项
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 固定行高：既给两侧渐隐一个确定画布，也避免 SubcomposeLayout 的固有尺寸问题
            .height(FilterRowHeight),
    ) {
        // 每项按文字自己的宽度排，间距固定 → 中间不出现被列宽撑出来的空档
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            row.options.forEach { option ->
                FilterChip(
                    label = option.label,
                    selected = option.value == row.selected,
                    onClick = { onSelect(row.kind, option.value) },
                )
            }
        }
        // 选项超出可视宽度时两侧出渐隐，滑到端头自动淡出（与首页分类行同一套）
        CategoryEdgeFade(visible = scrollState.canScrollBackward, fromStart = true)
        CategoryEdgeFade(visible = scrollState.canScrollForward, fromStart = false)
    }
}

/**
 * 筛选项。只有选中项套胶囊底，其余是纯文字——参考片库页的做法：
 * 一屏里要并排五六行，满屏色块会把下面的海报压下去。
 */
@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Surface(
            shape = AppShape.full,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier
                .clip(AppShape.full)
                .clickable(onClick = onClick),
        ) {
            ChipLabel(label = label, selected = true)
        }
    } else {
        Box(
            modifier = modifier
                .clip(AppShape.full)
                .clickable(onClick = onClick),
        ) {
            ChipLabel(label = label, selected = false)
        }
    }
}

@Composable
private fun ChipLabel(label: String, selected: Boolean) {
    Text(
        text = label,
        // 比正文小一档：一行六个维度叠起来，16sp 会显得压迫
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            starveilExtraColors.textMuted
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // 不撑满列槽：胶囊跟着文字走，位置由外层排列决定
        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
    )
}

/** 筛选行高度：胶囊 + 上下留白，同时给两侧渐隐一个确定的画布高度。 */
internal val FilterRowHeight: Dp = Spacing.xxl + Spacing.xxs
