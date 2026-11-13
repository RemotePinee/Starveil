package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.bold.Pause
import com.adamglin.phosphoricons.bold.Play
import com.adamglin.phosphoricons.regular.TelevisionSimple
import com.starveil.core.data.cast.CastRepository
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.CastPlaybackState
import com.starveil.core.model.CastUiStatus
import kotlinx.coroutines.launch

/**
 * 投屏中：整块盖住播放器画面，手机退化成遥控器。
 *
 * 层级只有三层：顶端一行状态、中间一台设备、底端两个动作。
 * 不再把头像是谁、片名多大全堆在左上角——那读起来像调试信息。
 */
@Composable
internal fun CastNowScreen(
    cast: CastRepository,
    target: CastTarget,
    onRestorePosition: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by cast.status.collectAsStateWithLifecycle()
    val session = (status as? CastUiStatus.Connected)?.session ?: return
    val scope = rememberCoroutineScope()
    val playing = session.state == CastPlaybackState.Playing

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CastingStatusRow()
        CastingIdentity(deviceName = session.device.name, title = target.title)
        CastingActions(
            playing = playing,
            onToggle = { scope.launch { if (playing) cast.pause() else cast.resume() } },
            onDisconnect = {
                scope.launch {
                    val position = cast.disconnect()
                    if (position > 0) onRestorePosition(position)
                }
            },
        )
    }
}

/** 顶端状态：一个圆点 + "正在投屏"，用来说清楚这屏不是本地在放。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CastingStatusRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.sm)
                .clip(AppShape.full)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = "正在投屏",
            style = MaterialTheme.typography.labelMedium,
            color = starveilExtraColors.textMuted,
        )
    }
}

/** 中间：设备图标 + 设备名 + 正在放的片名，居中。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CastingIdentity(deviceName: String, title: String) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.xxl * 2)
                .clip(AppShape.large)
                .background(starveilExtraColors.surfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PhosphorIcons.Regular.TelevisionSimple,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(IconSize.lg),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = deviceName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xxs),
        )
    }
}

/** 底端两个动作：暂停/继续是主操作，断开是次操作。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CastingActions(
    playing: Boolean,
    onToggle: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        CastAction(
            label = if (playing) "暂停" else "继续",
            icon = if (playing) PhosphorIcons.Bold.Pause else PhosphorIcons.Bold.Play,
            primary = true,
            modifier = Modifier.weight(1f),
            onClick = onToggle,
        )
        CastAction(
            label = "断开投屏",
            icon = null,
            primary = false,
            modifier = Modifier.weight(1f),
            onClick = onDisconnect,
        )
    }
}

/** 一个动作：主操作实底胶囊，次操作淡底 + 错误色文字。 */
@Composable
private fun CastAction(
    label: String,
    icon: ImageVector?,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(Spacing.xxl + Spacing.md)
            .clip(AppShape.full)
            .clickable(onClick = onClick),
        shape = AppShape.full,
        color = if (primary) {
            starveilExtraColors.surfaceCard
        } else {
            starveilExtraColors.surfaceCard.copy(alpha = ACTION_GHOST_ALPHA)
        },
        contentColor = if (primary) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.error
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.sm).padding(end = Spacing.xs),
                )
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** 次操作胶囊的底色浓度：比主操作淡，靠文字颜色区分。 */
private const val ACTION_GHOST_ALPHA = 0.5f
