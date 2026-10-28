package com.starveil.feature.shortplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.ChatCircle
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.Heart
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.starveil.core.model.ShortPlayType

@Composable
internal fun ShortPlayDrawer(
    state: ShortPlayUiState,
    actions: ShortPlayActions,
    navigation: ShortPlayNavigation,
    onAction: (() -> Unit) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxHeight()) {
        ModalDrawerSheet(
            modifier = Modifier.width((maxWidth * 0.72f).coerceAtMost(Spacing.rowHeight * 5)),
            drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            drawerContentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            ShortPlayDrawerContent(state, actions, navigation, onAction)
        }
    }
}

@Composable
private fun ShortPlayDrawerContent(
    state: ShortPlayUiState,
    actions: ShortPlayActions,
    navigation: ShortPlayNavigation,
    onAction: (() -> Unit) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        item {
            Text(
                "短剧", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
            ShortPlayDrawerSection("分类")
            // 分类是后台配的个位数列表，用流式药丸而不是整宽列表行：
            // 一行能放下 2-3 个，抽屉里省下的纵向空间给下面的常用入口。
            if (state.types.isEmpty()) {
                ShortPlayDrawerSection("后台暂无分类")
            } else {
                CategoryPillFlow(
                    types = state.types,
                    selectedTypeId = state.selectedTypeId,
                    onSelect = { id -> onAction { actions.onTypeSelect(id) } },
                )
            }
        }
        item {
            Spacer(Modifier.height(Spacing.lg))
            ShortPlayDrawerSection("常用")
            ShortPlayDrawerLinks(navigation, actions.onRefresh, onAction)
        }
    }
}

@Composable
private fun ShortPlayDrawerSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
    )
}

/**
 * 分类药丸：流式排布，一行放得下几个就放几个。
 *
 * 用 [FlowRow] 而不是横向滚动：抽屉本身不宽，横向滚动会把后面的分类藏起来，
 * 用户根本不知道还有；换行铺开一眼能看全。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPillFlow(
    types: List<ShortPlayType>,
    selectedTypeId: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        types.forEach { type ->
            CategoryPill(
                label = type.name.ifBlank { "剧集" },
                selected = type.id == selectedTypeId,
                onClick = { onSelect(type.id) },
            )
        }
    }
}

/**
 * 单个药丸标签。
 *
 * 选中时铺主色，未选中用 `surfaceContainerHigh`——比描边款在抽屉底色上更清楚，
 * 也不用为深浅两套主题各调一次边框透明度。
 */
@Composable
private fun CategoryPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = AppShape.full,
        color = if (selected) colors.primary else colors.surfaceContainerHigh,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        modifier = Modifier.semantics { this.selected = selected },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}

@Composable
private fun ShortPlayDrawerItem(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = AppShape.small,
        color = if (selected) colors.surfaceContainerHigh else colors.surfaceContainer,
        contentColor = if (selected) colors.primary else colors.onSurface,
    ) {
        Row(
            modifier = Modifier.heightIn(min = Spacing.rowHeight)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(IconSize.item))
            }
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (selected) {
                Icon(PhosphorIcons.Regular.Check, null, modifier = Modifier.size(IconSize.item))
            }
        }
    }
}

@Composable
private fun ShortPlayDrawerLinks(
    navigation: ShortPlayNavigation,
    onRefresh: () -> Unit,
    onAction: (() -> Unit) -> Unit,
) {
    ShortPlayDrawerItem(
        label = "观看历史", icon = PhosphorIcons.Regular.ClockCounterClockwise,
        onClick = { onAction(navigation.onHistory) },
    )
    ShortPlayDrawerItem(
        label = "我的收藏", icon = PhosphorIcons.Regular.Heart,
        onClick = { onAction(navigation.onFavorites) },
    )
    ShortPlayDrawerItem(
        label = "反馈", icon = PhosphorIcons.Regular.ChatCircle,
        onClick = { onAction(navigation.onFeedback) },
    )
    ShortPlayDrawerItem(
        label = "换一批", icon = PhosphorIcons.Regular.ArrowsClockwise,
        onClick = { onAction(onRefresh) },
    )
}
