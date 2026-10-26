package com.starveil.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.Waveform
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.LiveChannel

/**
 * 直播卡（网格用）：上图下名。
 *
 * 之前改过一版横排长条，一屏只放得下三四个台；后台导几百个频道时翻找太慢。
 * 现在按两列网格排，封面 16:9、名称两行、分组名做副标题——
 * 一屏能看到 6~8 个台，扫视效率接近电视墙。
 */
@Composable
internal fun LiveChannelCard(
    channel: LiveChannel,
    playback: LivePlaybackState?,
    onClick: () -> Unit,
) {
    val playing = playback != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.medium)
            .background(starveilExtraColors.surfaceCard)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(THUMB_RATIO)
                .background(
                    Brush.verticalGradient(
                        0f to starveilExtraColors.surfaceElevated,
                        1f to starveilExtraColors.surfaceCard,
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!channel.cover.isNullOrBlank()) {
                AsyncImage(
                    model = channel.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = PhosphorIcons.Regular.Waveform,
                    contentDescription = null,
                    tint = starveilExtraColors.textFaint,
                    modifier = Modifier.size(IconSize.lg),
                )
            }
            LiveBadge(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Spacing.xs),
            )
            if (playing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(starveilExtraColors.scrimStrong.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = PhosphorIcons.Regular.Play,
                        contentDescription = "正在播放",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(IconSize.lg),
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(Spacing.xxs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(Spacing.xs)
                        .clip(AppShape.full)
                        .background(starveilExtraColors.accent),
                )
                Spacer(Modifier.width(Spacing.xxs))
                Text(
                    text = channel.group.ifBlank { "直播中" },
                    style = MaterialTheme.typography.bodySmall,
                    color = starveilExtraColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 缩略图 16:9：台标清晰度不一，统一比例才不会把网格撑得参差不齐。 */
private const val THUMB_RATIO = 16f / 9f

/**
 * 「LIVE」角标：页头、网格卡、选台面板共用。
 */
@Composable
internal fun LiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(AppShape.small)
            .background(starveilExtraColors.accent)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
    ) {
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.background,
            maxLines = 1,
        )
    }
}
