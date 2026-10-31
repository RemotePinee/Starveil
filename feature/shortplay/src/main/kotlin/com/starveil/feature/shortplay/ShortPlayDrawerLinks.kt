package com.starveil.feature.shortplay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.ChatCircle
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.Heart
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 固定尾：快捷入口。
 *
 * 与分类区用一条分隔线断开，并且不参与滚动——分类再多也压不到这里。
 * 每行带右向箭头，读起来是"可以进去"，而不是四个平铺的文字。
 */
@Composable
internal fun ShortPlayDrawerFooter(
    navigation: ShortPlayNavigation,
    onRefresh: () -> Unit,
    onAction: (() -> Unit) -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.padding(vertical = Spacing.sm)) {
        DrawerLink("观看历史", PhosphorIcons.Regular.ClockCounterClockwise) { onAction(navigation.onHistory) }
        DrawerLink("我的收藏", PhosphorIcons.Regular.Heart) { onAction(navigation.onFavorites) }
        DrawerLink("反馈", PhosphorIcons.Regular.ChatCircle) { onAction(navigation.onFeedback) }
        DrawerLink("换一批", PhosphorIcons.Regular.ArrowsClockwise) { onAction(onRefresh) }
    }
}

/**
 * 一行快捷入口。
 *
 * 不带容器底色：整行铺 `Surface` 会看起来像一排按钮，侧栏里的动作应当是
 * "列表行 + 点击反馈"，而不是四张卡片。
 */
@Composable
private fun DrawerLink(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = Spacing.rowHeight)
            .clip(AppShape.small)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            icon,
            // 行内已有文字，图标不再重复朗读。
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(IconSize.item),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            letterSpacing = 0.1.sp,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            PhosphorIcons.Regular.CaretRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(IconSize.sm),
        )
    }
}
