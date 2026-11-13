package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import com.starveil.core.data.cast.CastRepository
import com.starveil.core.designsystem.LocalSkinSelection
import com.starveil.core.designsystem.StarveilTheme
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.launch

/**
 * 点投屏胶囊后弹出的设备面板；打开即搜索。
 *
 * 形态是贴底的抽屉，不是居中对话框：投屏是"从这一屏挑一台设备"的临时动作，
 * 抽屉把重量放在屏幕下半、上面还留一截能看见画面，也是各家投屏面板的通用形态。
 */
@Composable
internal fun CastDeviceDialog(
    cast: CastRepository,
    target: CastTarget,
    onDismiss: () -> Unit,
    onConnected: () -> Unit,
) {
    val status by cast.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var relay by remember { mutableStateOf(false) }
    LaunchedEffect(cast) { cast.search() }

    Dialog(
        onDismissRequest = onDismiss,
        // 默认宽度是系统给的一个小方块：抽屉要自己铺满整个窗口再往下靠。
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        StarveilTheme(
            skinCode = LocalSkinSelection.current.effective,
            darkTheme = isSystemInDarkTheme(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 遮罩：点它关闭。只压暗，不做毛玻璃——抽屉的边界靠圆角和底色区分。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(starveilExtraColors.scrimStrong.copy(alpha = SHEET_SCRIM_ALPHA))
                        .clickable(onClick = onDismiss),
                )
                CastSheet(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    title = "投屏到电视",
                    subtitle = "选择同一 Wi-Fi 下的设备",
                    onDismiss = onDismiss,
                ) {
                    RelayRow(checked = relay, onCheckedChange = { relay = !relay })
                    SheetDivider()
                    DevicesSection(
                        status = status,
                        onRetry = { scope.launch { cast.search() } },
                        onPick = { device ->
                            scope.launch {
                                val url = if (relay) cast.relayUrlFor(target.url) else target.url
                                if (url != null && cast.castTo(device, url, target.title)) onConnected()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 贴底抽屉的外壳：抓手、标题、内容。高度由内容撑开。 */
@Composable
private fun CastSheet(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShape.sheetTopLarge,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Box(
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .align(Alignment.CenterHorizontally)
                    .width(Spacing.xxl + Spacing.xs)
                    .height(Spacing.xs)
                    .clip(AppShape.full)
                    .background(starveilExtraColors.divider),
            )
            SheetHeader(title = title, subtitle = subtitle, onDismiss = onDismiss)
            content()
        }
    }
}

/** 标题区：主标题 + 一句用途说明，右上角关闭。 */
@Composable
private fun SheetHeader(title: String, subtitle: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
        SheetCloseButton(onClick = onDismiss)
    }
}

/** 关闭：40dp 触摸区，只有图标没有底板，和抽屉的轻量语气一致。 */
@Composable
private fun SheetCloseButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(Spacing.xxl + Spacing.sm)
            .clip(AppShape.full)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PhosphorIcons.Regular.X,
            contentDescription = "关闭",
            tint = starveilExtraColors.textMuted,
            modifier = Modifier.size(IconSize.sm),
        )
    }
}

/** 中转开关：一行两段文案 + 右侧开关，上下用发丝线隔开，不做卡片。 */
@Composable
private fun RelayRow(checked: Boolean, onCheckedChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.md)) {
            Text(
                text = "中转投屏",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "电视连不上片源时，由手机代为中转",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            // 浅色模式下默认 thumb 会和底色融在一起，这里显式给两端颜色
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

/** 发丝线：抽屉里的分组边界，靠线不靠色块。 */
@Composable
internal fun SheetDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.hairline)
            .background(starveilExtraColors.divider),
    )
}

/** 遮罩浓度：只压暗，不用毛玻璃。 */
private const val SHEET_SCRIM_ALPHA = 0.6f
