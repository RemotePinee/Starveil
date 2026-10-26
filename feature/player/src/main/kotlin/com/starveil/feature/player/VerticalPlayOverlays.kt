package com.starveil.feature.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.TrySeeAccess

/**
 * 竖屏页的覆盖层组件集合：视频面、进度条、暂停提示、三态提示、试看遮挡、顶栏、底部信息条。
 *
 * 单独成文件是为了守住 450 行闸门；这些组件都只被 [VerticalPlayScreen] 的覆盖层用到。
 */

/** 实际视频矩形始终在容器内居中，宽屏与竖屏内容都只 fit，不拉伸、不裁切。 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoStage(engine: Media3Player) {
    val ratio = engine.videoAspectRatio
    Layout(
        content = {
            // 同一引擎跨集保帧；换剧换引擎才重建输出，杜绝复用旧 TextureView 的跨剧残影。
            key(engine) {
                ContentFrame(
                    player = engine.mediaPlayer,
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    contentScale = ContentScale.FillBounds,
                    keepContentOnReset = true,
                    shutter = {},
                )
            }
        },
        modifier = Modifier.fillMaxSize().background(starveilExtraColors.scrimStrong),
    ) { measurables, constraints ->
        val frame = fitVerticalVideo(constraints.maxWidth, constraints.maxHeight, ratio)
        val surface = measurables.single().measure(Constraints.fixed(frame.width, frame.height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            surface.placeRelative(
                (constraints.maxWidth - frame.width) / 2,
                (constraints.maxHeight - frame.height) / 2,
            )
        }
    }
}

/** 复用正剧的细轨道与可拖动圆点；位置/时长仍来自 500ms 采样。 */
@Composable
internal fun ProgressBar(
    state: VerticalPlayUiState,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit = {},
) {
    PlayerProgressBar(
        positionMs = state.positionMs,
        durationMs = state.durationMs,
        contentColor = starveilExtraColors.onImage,
        onSeekTo = onSeek,
        modifier = modifier,
        compact = true,
    )
}

/** 暂停提示与右侧操作栏统一使用圆润实心图标；透明触摸区不遮挡视频。 */
@Composable
internal fun PauseHint(onPlayPause: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(Spacing.rowHeight)
            .clip(AppShape.full)
            // 触控面积仍是 52dp 圆形；role 由 verticalPress 默认给 Role.Button。
            .verticalPress(onClick = onPlayPause),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PhosphorIcons.Fill.Play,
            contentDescription = "播放",
            tint = starveilExtraColors.onImage,
            modifier = Modifier.size(IconSize.xl),
        )
    }
}

/**
 * 试看到点的覆盖层。
 *
 * 只负责"停下 + 给出口"：会员走会员下单页，单集付费带上网址参数；
 * 逐集解锁状态机（广告解锁、换签）归计划 §5 P4，本页不缓存未解锁集的地址。
 */
@Composable
internal fun TrySeeOverlay(
    access: TrySeeAccess?,
    episodeNumber: Int,
    actions: VerticalPlayActions,
    unlock: UnlockOptions,
    preview: Boolean = false,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(starveilExtraColors.heroScrimEnd),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = trySeeNoticeOf(access ?: TrySeeAccess.FREE, unlock),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = starveilExtraColors.onImage,
            )
            // 三个出口同权重：同尺寸实心按钮，顺序固定会员、积分、广告。
            // 会员关闭时 canUpgrade=0；广告未接入时 canWatchAd=0，各自不渲染。
            if (unlock.canUpgrade) {
                UnlockAction(label = "开通会员") { actions.onUpgrade(TrySeeAccess.NEED_VIP, episodeNumber) }
            }
            if (unlock.canRedeemPoints) {
                UnlockAction(label = redeemLabelOf(unlock.pointsPrice)) {
                    actions.onUpgrade(TrySeeAccess.NEED_PAY, episodeNumber)
                }
            }
            if (unlock.canWatchAd) {
                UnlockAction(label = "看广告解锁本集", onClick = actions.onWatchAd)
            }
            if (!preview) TextButton(onClick = actions.onBack) { Text(text = "返回剧场") }
        }
    }
}

@Composable
private fun UnlockAction(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(top = Spacing.sm)) { Text(text = label) }
}

private fun redeemLabelOf(points: Int): String =
    if (points > 0) "$points 积分兑换本集" else "积分兑换本集"

/** 剧内顶栏；预览模式完全不绘制。 */
@Composable
internal fun EpisodeTopBar(
    episodeNumber: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // RTL 下 Row 从右往左排，退让方向跟着翻，否则同一个负偏移会把缝掰得更宽。
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.heightIn(min = Spacing.rowHeight)) {
            Icon(
                imageVector = PhosphorIcons.Regular.ArrowLeft,
                contentDescription = "返回",
                tint = starveilExtraColors.onImage,
                modifier = Modifier.size(IconSize.md).mirrorIconInRtl(),
            )
        }
        // 返回按钮的 52dp 触摸区不缩；IconButton 自带左右内边距，这里再左移 sm，
        // 让图标与集数只隔一层 xs 级视觉缝，行间不再额外叠 spacedBy。
        Text(
            text = "第 $episodeNumber 集",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = starveilExtraColors.onImage,
            maxLines = 1,
            modifier = Modifier.offset(x = if (rtl) Spacing.sm else -Spacing.sm),
        )
    }
}

