package com.starveil.feature.shortplay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.X
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/** 窄屏按比例收，别把视频压成一条；宽屏封顶见 [Spacing.drawerWidth]。 */
private const val DRAWER_WIDTH_RATIO = 0.78f

/** 大标题收紧字距：默认字距在大字号下发散，收 0.4sp 才像出版物标题。 */
private val TitleTracking = (-0.4).sp

/** 面板左侧为超拉预留的同色长度，覆盖手势多拖出来的那几像素。 */
private val OverscrollBleed = Spacing.xxl

/**
 * 剧场页侧栏。
 *
 * 三段式：固定头（标题 + 关闭）、可滚的分类区、固定的快捷入口。分类完全按后台
 * 下发顺序全量展示——不合并、不截断、不重排，多了只滚中间那段，头尾永远在位。
 *
 * 视觉上刻意不画任何色块：分类只靠颜色、字重与一条 2dp 指示线区分选中，
 * 卡片底色 + 圆角方块拼在一起像设置页，侧栏要的是目录感。
 *
 * 宿主已经把页面铺到全屏（见 `Modifier.bleedBottom`），所以这里自己补系统栏
 * inset；侧栏打开时壳层把 dock 淡出，视觉上是一整块侧栏而不是半截面板。
 */
@Composable
internal fun ShortPlayDrawer(
    state: ShortPlayUiState,
    actions: ShortPlayActions,
    navigation: ShortPlayNavigation,
    dockInset: Dp = Spacing.none,
    onSearchClick: () -> Unit = {},
    onClose: () -> Unit = {},
    onAction: (() -> Unit) -> Unit,
) {
    val sheetColor = MaterialTheme.colorScheme.surface
    BoxWithConstraints(Modifier.fillMaxHeight()) {
        ModalDrawerSheet(
            modifier = Modifier
                .width((maxWidth * DRAWER_WIDTH_RATIO).coerceAtMost(Spacing.drawerWidth))
                // 抽屉可以轻微超拉：面板右移几像素，左缘就离开屏幕边，露出底下视频。
                // 在面板左侧补画一截同色，多拉出来的距离仍被面板盖住。
                .drawBehind {
                    val bleed = OverscrollBleed.toPx()
                    drawRect(color = sheetColor, topLeft = Offset(-bleed, 0f), size = Size(bleed, size.height))
                },
            drawerContainerColor = sheetColor,
            drawerContentColor = MaterialTheme.colorScheme.onSurface,
            // M3 默认还会再叠一层状态栏 inset：宿主已经扣过一次，这里归零、由内容自己补。
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            ShortPlayDrawerPanel(state, actions, navigation, dockInset, onSearchClick, onClose, onAction)
        }
    }
}

@Composable
private fun ShortPlayDrawerPanel(
    state: ShortPlayUiState,
    actions: ShortPlayActions,
    navigation: ShortPlayNavigation,
    dockInset: Dp = Spacing.none,
    onClose: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onAction: (() -> Unit) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            // dock 叠在页面之上，侧栏内容让出它的高度，最后一项才不会被压住。
            .padding(bottom = dockInset)
            .padding(horizontal = Spacing.lg),
    ) {
        ShortPlayDrawerHeader(onClose)
        Spacer(Modifier.height(Spacing.md))
        ShortPlayDrawerSearch(onClick = onSearchClick)
        Spacer(Modifier.height(Spacing.sm))
        CategoryGrid(
            types = state.types,
            selectedTypeId = state.selectedTypeId,
            onSelect = { id -> onAction { actions.onTypeSelect(id) } },
            // fill = false：分类少时按内容高排版，分隔线与入口紧跟着上来，
            // 不在中间留出一大块；分类多时被剩余高度封顶，分类区自己滚。
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.height(Spacing.md))
        ShortPlayDrawerFooter(navigation, actions.onRefresh, onAction)
    }
}

@Composable
private fun ShortPlayDrawerHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "短剧",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = TitleTracking,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(
                PhosphorIcons.Regular.X,
                contentDescription = "关闭菜单",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.item),
            )
        }
    }
}

/**
 * 侧栏顶部搜索入口。
 *
 * 头部原先只有一行标题和一个关闭按钮，下面直接跳分类，视觉上是空的；搜索行既
 * 填住了这块，也是这个侧栏里最常被用到的动作（分类再多也不如直接搜）。
 */
@Composable
private fun ShortPlayDrawerSearch(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(AppShape.small)
            .clickable(onClickLabel = "搜索短剧", onClick = onClick)
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            PhosphorIcons.Regular.MagnifyingGlass,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(IconSize.sm),
        )
        Text(
            text = "搜索短剧、演员",
            style = MaterialTheme.typography.bodyMedium,
            letterSpacing = 0.1.sp,
            color = colors.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        color = colors.outlineVariant,
        modifier = Modifier.padding(top = Spacing.sm),
    )
}
