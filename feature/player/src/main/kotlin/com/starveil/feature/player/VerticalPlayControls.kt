package com.starveil.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.ChatCircleDots
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.ShareFat
import com.adamglin.phosphoricons.fill.Speedometer
import com.starveil.core.designsystem.component.StarveilLoading
import com.starveil.core.designsystem.interaction.HapticKind
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.TrySeeAccess

/**
 * 右栏上抛的动作标签。
 *
 * 右栏只把点击上抛成字符串，真实能力归应用壳（AGENTS 第 1 节：feature 不持有导航与仓库）。
 * 标签集中在这里，`ShortPlayInteractions` 与评论抽屉按常量分流，避免两端各写一份中文字面量。
 * 「反馈」已从右栏撤掉：它的入口留在短剧抽屉（`ShortPlayDrawer`）。
 */
const val VERTICAL_ACTION_FAVORITE = "收藏"
const val VERTICAL_ACTION_COMMENT = "评论"
const val VERTICAL_ACTION_SHARE = "分享"

/** 右侧操作栏宽度（计划 §5 P0：`Spacing.xxl * 2`）。标题据此让开右侧。 */
internal val SIDE_RAIL_WIDTH: Dp = Spacing.xxl * 2

/** 底部集数条高度（计划 §5 P0：`Spacing.xxl`）。 */
internal val EPISODE_STRIP_HEIGHT: Dp = Spacing.xxl

/** 海报底部渐隐高度。 */
internal val POSTER_FADE_HEIGHT: Dp = Spacing.xxl * 2

/**
 * 竖屏页的交互集合。与 `PlayerActions` 同一做法：参数到 5 个就收敛成动作包，
 * 组件签名不随交互增加而变长，也满足 detekt `LongParameterList`。
 */
@Immutable
data class VerticalPlayActions(
    val onBack: () -> Unit,
    val onPlayPause: () -> Unit,
    val onToggleEpisodes: () -> Unit,
    val onEpisodeSelect: (Int) -> Unit,
    val onUpgrade: (TrySeeAccess, Int) -> Unit,
    val onRetry: () -> Unit,
    /**
     * 看广告解锁（预留）。广告接入后由 `:app` 完成"看广告 → 服务端换签 → 重查闸门"，
     * 本模块只负责上抛点击；当前服务端 `can_ad=0`，按钮不会出现。
     */
    val onWatchAd: () -> Unit = {},
    /** 倍速：点击在几档之间循环，直接下发给播放器。 */
    val onSpeedSelect: (Float) -> Unit = {},
    /**
     * 仅上抛 [VERTICAL_ACTION_FAVORITE] / [VERTICAL_ACTION_COMMENT] / [VERTICAL_ACTION_SHARE]；
     * 真实能力归应用壳：评论由应用壳就地弹出评论抽屉，不跳路由、不动播放器。
     */
    val onAction: (String) -> Unit = {},
    val onOpenSeries: () -> Unit = {},
    val onToggleRecommendations: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
)

/**
 * 右侧真实能力栏：收藏、评论、分享，最后是倍速档位。
 *
 * 顺序对应「表达 → 互动 → 传播 → 播放设置」；没有虚构的点赞与计数。
 * 评论不在本层持有面板状态：点一下只上抛 [VERTICAL_ACTION_COMMENT]，弹层归应用壳。
 */
@Composable
internal fun VerticalSideRail(
    stage: VerticalPlayStage,
    modifier: Modifier = Modifier,
) {
    val options = stage.options
    val actions = stage.actions
    Column(
        modifier = modifier
            .width(SIDE_RAIL_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        RailItem(
            icon = PhosphorIcons.Fill.Heart,
            label = if (options.isFavorite) "已收藏" else VERTICAL_ACTION_FAVORITE,
            onClick = { actions.onAction(VERTICAL_ACTION_FAVORITE) },
            busy = options.pendingAction == VERTICAL_ACTION_FAVORITE,
            enabled = options.pendingAction == null,
            // 收藏是右栏唯一带选中态的能力：选中走会员金，其余保持画面白。
            tint = if (options.isFavorite) starveilExtraColors.brandGold else null,
        )
        // 评论不吃 pendingAction：它不是写操作，弹层开合与收藏/分享的串行锁无关。
        RailItem(
            PhosphorIcons.Fill.ChatCircleDots, VERTICAL_ACTION_COMMENT,
            { actions.onAction(VERTICAL_ACTION_COMMENT) },
        )
        RailItem(
            PhosphorIcons.Fill.ShareFat, VERTICAL_ACTION_SHARE,
            { actions.onAction(VERTICAL_ACTION_SHARE) },
            busy = options.pendingAction == VERTICAL_ACTION_SHARE,
            enabled = options.pendingAction == null,
        )
        SpeedItem(stage.speed) { actions.onSpeedSelect(nextSpeed(stage.speed)) }
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    busy: Boolean = false,
    enabled: Boolean = true,
    /** 选中态染色（收藏金色）；null 表示画面白。 */
    tint: Color? = null,
) {
    val contentColor = tint ?: starveilExtraColors.onImage
    Column(
        modifier = Modifier
            .width(SIDE_RAIL_WIDTH)
            .heightIn(min = Spacing.rowHeight)
            .clip(AppShape.full)
            .semantics { stateDescription = if (busy) "处理中" else label }
            .verticalPress(onClick = onClick, enabled = enabled)
            .padding(vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        if (busy) {
            VerticalActionLoading(Modifier.size(IconSize.xl))
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(IconSize.xl),
            )
        }
        RailLabel(if (busy) "处理中" else label, contentColor)
    }
}

/**
 * 倍速：表盘图标在上、当前档位在下，与其余能力的"图标 + 文案"同高一列。
 *
 * 1x 是默认档，表盘已经说明这是倍速，不再在下面叠一行"倍速"文案；
 * 两行合成一句无障碍描述"倍速，当前1x"。
 */
@Composable
private fun SpeedItem(speed: Float, onClick: () -> Unit) {
    val label = railSpeedLabel(speed)
    Column(
        modifier = Modifier
            .width(SIDE_RAIL_WIDTH)
            .heightIn(min = Spacing.rowHeight)
            .clip(AppShape.full)
            .semantics(mergeDescendants = true) { contentDescription = "倍速，当前$label" }
            .verticalPress(onClick = onClick)
            .padding(vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Icon(
            imageVector = PhosphorIcons.Fill.Speedometer,
            contentDescription = null,
            tint = starveilExtraColors.onImage,
            modifier = Modifier.size(IconSize.xl),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = starveilExtraColors.onImage,
            maxLines = 1,
        )
    }
}

@Composable
private fun RailLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Medium,
    )
}

/** 统一加载组件放入固定图标区；空文案区不占位，pending 不改变右栏高度。 */
@Composable
internal fun VerticalActionLoading(modifier: Modifier = Modifier) {
    Box(modifier.clipToBounds()) {
        StarveilLoading(modifier = Modifier.matchParentSize(), message = "")
    }
}

/** 按压只变绘制比例，不改变布局尺寸；同一反馈用于视频控件和选集。 */
@Composable
internal fun Modifier.verticalPress(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val scale = verticalPressScale(interaction)
    val haptics = rememberHaptics()
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
        ) {
            haptics(HapticKind.Tick)
            onClick()
        }
}

@Composable
internal fun verticalPressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = Motion.short,
        label = "vertical-control-press",
    )
    return scale
}

/** 右栏档位读数：整数档写 "1x"，非整数档沿用 [speedLabel] 的 "1.25x"。 */
private fun railSpeedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else speedLabel(speed)

/** 1.0x → 1.25x → 1.5x → 2.0x → 0.75x → 1.0x，五档循环。 */
internal fun nextSpeed(speed: Float): Float {
    val index = SPEED_STEPS.indexOfFirst { it == speed }
    return SPEED_STEPS[(index + 1).mod(SPEED_STEPS.size)]
}

private val SPEED_STEPS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
