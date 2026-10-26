package com.starveil.feature.user

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.ChatText
import com.adamglin.phosphoricons.regular.Gear
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 功能列表：观看记录 / 设置 / 反馈，一张分组卡片里的三行。
 *
 * 行高固定 [Spacing.rowHeight]（52dp）、图标 [IconSize.item]（20dp）、右侧箭头 [IconSize.sm]，
 * 三行尺寸一致，滚动时不会因为文案长短抖动；分隔线缩进到文字左缘，和图标列对齐。
 *
 * "设置"由 `:app` 映射到 `PROFILE_ROUTE`。观看记录与反馈当前没有注册路由，这里保留视觉项
 * 并照常上抛文案：路由补齐时只在 `:app` 的映射里加一行，页面不用动。不自造导航、不留空回调。
 */
@Composable
internal fun UserCenterFeatureList(onEntryClick: (String) -> Unit) {
    Surface(
        shape = AppShape.extraLarge,
        color = starveilExtraColors.surfaceCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            FeatureRow(
                icon = PhosphorIcons.Regular.Gear,
                label = LABEL_SETTINGS,
                onClick = { onEntryClick(ENTRY_SETTINGS) },
            )
            RowDivider()
            FeatureRow(
                icon = PhosphorIcons.Regular.ChatText,
                label = LABEL_FEEDBACK,
                onClick = { onEntryClick(ENTRY_FEEDBACK) },
            )
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.rowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(TILE_SIZE)
                .clip(AppShape.medium)
                .background(starveilExtraColors.chipBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = starveilExtraColors.textMuted,
                modifier = Modifier.size(IconSize.item),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(WEIGHT_FULL),
        )
        Icon(
            imageVector = PhosphorIcons.Regular.CaretRight,
            contentDescription = null,
            tint = starveilExtraColors.textFaint,
            modifier = Modifier.size(IconSize.sm).mirrorIconInRtl(),
        )
    }
}

/** 行间分隔线缩进到文字左缘，和图标列对齐。 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = starveilExtraColors.divider,
        modifier = Modifier.padding(start = Spacing.lg + TILE_SIZE + Spacing.md, end = Spacing.lg),
    )
}

private val TILE_SIZE = Spacing.xxl + Spacing.xs
private const val LABEL_SETTINGS = "设置"
private const val LABEL_FEEDBACK = "反馈"
private const val ENTRY_SETTINGS = "设置"
private const val ENTRY_FEEDBACK = "反馈"
private const val WEIGHT_FULL = 1f
