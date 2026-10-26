package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check

/** 选中项底色：主色压到 16%，倍速这类列表用它，比实底克制。 */
private const val SELECTED_BG_ALPHA = 0.16f

/** 列表行高 48dp = 32 + 16，与选集单元同档。 */
private val RowHeight: Dp = Spacing.xxl + Spacing.lg

/** 顶部拖拽条 32 × 4dp。 */
private val DragHandleWidth: Dp = Spacing.xxl

private val DragHandleHeight: Dp = Spacing.xs

/** 列表选中项左侧的主色条。 */
private val SelectedBarWidth: Dp = Spacing.xs

/** 播放页的底部弹层：选集（内含线路 Tab）、倍速、弹幕。 */
internal enum class PlayerSheetKind { Episodes, Speed, Danmu }

/**
 * 弹层宿主：拖拽条 + 统一的圆角实底。
 *
 * 选集层固定占屏高 45%（见 [EpisodeSheet]），内容在层内滚动；倍速层与弹幕层
 * 按内容自适应高度。三个弹层共用同一个宿主，视觉只有一套。
 *
 * @param danmu 弹幕层的当前选择。[kind] 不是 [PlayerSheetKind.Danmu] 时可以为 null。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheetHost(
    kind: PlayerSheetKind?,
    state: PlayerUiState,
    actions: PlayerActions,
    onDismiss: () -> Unit,
    danmu: DanmuSettings? = null,
    immersive: Boolean = false,
) {
    if (kind == null) return
    // 直接展开到全高：选集要一次看到当前集附近，半开位置还得再拖一下
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = AppShape.large,
        dragHandle = { SheetDragHandle() },
    ) {
        HideDialogSystemBars(immersive = immersive)
        when (kind) {
            PlayerSheetKind.Episodes -> EpisodeSheet(state = state, actions = actions, onDismiss = onDismiss)
            PlayerSheetKind.Speed -> SpeedList(state = state, actions = actions, onDismiss = onDismiss)
            PlayerSheetKind.Danmu -> danmu?.let {
                DanmuSheetContent(settings = it, onSend = actions.onSendDanmu)
            }
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

/**
 * 弹层是独立的 Dialog 窗口，自带一份系统栏状态：全屏时它会把自己的系统栏显示出来，
 * 一开选集/弹幕面板，状态栏就重新压在海报上。这里把弹层窗口的系统栏也隐藏掉。
 */
@Composable
private fun HideDialogSystemBars(immersive: Boolean) {
    if (!immersive) return
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
}

/** 顶部拖拽条：给"这层可以往下滑"一个明确的视觉抓手。 */
@Composable
private fun SheetDragHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(DragHandleWidth, DragHandleHeight)
                .clip(AppShape.full)
                .background(starveilExtraColors.divider),
        )
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
}

/** 倍速层：当前档主色底 + 左侧条 + 对勾，选中即生效并关层。 */
@Composable
private fun SpeedList(state: PlayerUiState, actions: PlayerActions, onDismiss: () -> Unit) {
    SheetTitle(text = "倍速")
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
        PlayerSpeeds.STEPS.forEach { speed ->
            OptionRow(
                label = speedLabel(speed),
                selected = speed == state.speed,
                onClick = {
                    onDismiss()
                    actions.onSpeedSelected(speed)
                },
            )
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .clip(AppShape.small)
            .background(if (selected) accent.copy(alpha = SELECTED_BG_ALPHA) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .width(SelectedBarWidth)
                .height(Spacing.xl)
                .clip(AppShape.full)
                .background(if (selected) accent else Color.Transparent),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = PhosphorIcons.Regular.Check,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(IconSize.item),
            )
        }
    }
}
