package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.ChatText
import com.adamglin.phosphoricons.regular.Coin
import com.adamglin.phosphoricons.regular.Gear
import com.adamglin.phosphoricons.regular.Ticket
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/** 常用工具的一行：图标 + 文案 + 可选行尾数值 + 右箭头。 */
private class ToolEntry(
    val icon: ImageVector,
    val label: String,
    val entry: String,
    /** 行尾数值（目前只有金币余额）；null 表示纯入口。 */
    val value: String? = null,
)

/**
 * 常用工具列表。
 *
 * 收藏与金币从原来的四个圆形快捷入口并进这一组：它们和设置、反馈一样是"一次性动作"，
 * 单独占一排图标反而把页面切成三段。观看记录另有独立区块（带进度卡片），不在这里。
 *
 * 会员关闭时整组不出现「卡密兑换」：它落在会员下单页里，会员页没了点进去只会是空页。
 *
 * @param goldLabel 金币余额（`cuican_user_ext.gold` 原样字符串）；接口没给时为 null，行尾不显示数值
 */
@Composable
internal fun UserCenterTools(
    onEntryClick: (String) -> Unit,
    memberEnabled: Boolean,
    goldLabel: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = AppShape.extraLarge,
        color = starveilExtraColors.surfaceCard,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            toolEntries(memberEnabled = memberEnabled, goldLabel = goldLabel).forEach { tool ->
                ToolRow(tool = tool, onClick = { onEntryClick(tool.entry) })
            }
        }
    }
}

/** 顺序即页面顺序：收藏 → 金币 → （卡密）→ 设置 → 反馈。 */
private fun toolEntries(memberEnabled: Boolean, goldLabel: String?): List<ToolEntry> = buildList {
    add(ToolEntry(PhosphorIcons.Fill.Heart, ENTRY_FAVORITE, ENTRY_FAVORITE))
    add(ToolEntry(PhosphorIcons.Regular.Coin, LABEL_GOLD, ENTRY_GOLD, value = goldLabel))
    if (memberEnabled) add(ToolEntry(PhosphorIcons.Regular.Ticket, ENTRY_CARD, ENTRY_CARD))
    add(ToolEntry(PhosphorIcons.Regular.Gear, ENTRY_SETTINGS, ENTRY_SETTINGS))
    add(ToolEntry(PhosphorIcons.Regular.ChatText, ENTRY_FEEDBACK, ENTRY_FEEDBACK))
}

@Composable
private fun ToolRow(tool: ToolEntry, onClick: () -> Unit) {
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
                imageVector = tool.icon,
                contentDescription = null,
                tint = starveilExtraColors.textMuted,
                modifier = Modifier.size(IconSize.item),
            )
        }
        Text(
            text = tool.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(WEIGHT_FULL),
        )
        val value = tool.value
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = starveilExtraColors.brandGold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = PhosphorIcons.Regular.CaretRight,
            contentDescription = null,
            tint = starveilExtraColors.textFaint,
            modifier = Modifier.size(IconSize.sm).mirrorIconInRtl(),
        )
    }
}

private val TILE_SIZE = Spacing.xxl + Spacing.xs
private const val WEIGHT_FULL = 1f
private const val LABEL_GOLD = "金币"
private const val ENTRY_FAVORITE = "我的收藏"
private const val ENTRY_GOLD = "金币提现"
private const val ENTRY_CARD = "卡密兑换"
private const val ENTRY_SETTINGS = "设置"
private const val ENTRY_FEEDBACK = "反馈"
