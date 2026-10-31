package com.starveil.feature.shortplay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ShortPlayType

private const val CATEGORY_COLUMNS = 2

/** 44dp 两列：一屏能放下后台常见的 20 个分类，再多多也只滚这一段。 */
private val CategoryRowHeight = Spacing.rowHeight - Spacing.sm

/** 选中指示线：短、细、贴着文字左缘，像目录里的当前章。 */
private val CategoryIndicatorWidth = Spacing.lg

/**
 * 分类区：全量、按后台顺序、两列。
 *
 * 分类是后台配置的（`shortplay_type_id` 逗号串），客户端不合并、不截断、
 * 不按名字分组——多了就让这段滚。
 *
 * 刻意不用 `LazyVerticalGrid`：它必须拿到确定高度才能排版，会把父级剩下的
 * 高度全部吃掉，于是分类只有十来项时，分隔线上方会空出一大块。这里改成
 * 普通的行式两列 + 外层滚动，分类少时高度就等于内容高度，分隔线与入口
 * 紧跟着上来；分类多时高度被封顶，这一段自己滚。
 */
@Composable
internal fun CategoryGrid(
    types: List<ShortPlayType>,
    selectedTypeId: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            text = "分类",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
        )
        if (types.isEmpty()) {
            Text(
                "后台暂无分类",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
            )
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            types.chunked(CATEGORY_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    row.forEach { type ->
                        CategoryCell(
                            label = type.name.ifBlank { "剧集" },
                            selected = type.id == selectedTypeId,
                            onClick = { onSelect(type.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(CATEGORY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * 单个分类：纯文字 + 选中时的一条 2dp 指示线。
 *
 * 不铺底色、不画边框：两列方块各自带容器色时，整块区域会读成"设置项"，
 * 而且选中块与未选中块的色差会把注意力从内容本身抢走。
 */
@Composable
private fun CategoryCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .heightIn(min = CategoryRowHeight)
            .clip(AppShape.small)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm)
            .semantics { this.selected = selected },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            letterSpacing = 0.1.sp,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .padding(top = Spacing.xs)
                .width(CategoryIndicatorWidth)
                .height(Spacing.xxs)
                .background(if (selected) colors.primary else Color.Transparent),
        )
    }
}
