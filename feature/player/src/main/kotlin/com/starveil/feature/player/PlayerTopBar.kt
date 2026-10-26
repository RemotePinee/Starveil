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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.Subtitles
import com.adamglin.phosphoricons.regular.SubtitlesSlash
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 顶部渐变区：96dp，压得住画面又不至于吃掉半屏。
 *
 * 暂停贴片也读它来给关闭键让位（见 [PlayerPauseAd]），所以不能再是文件私有。
 */
internal val TopScrimHeight: Dp = Spacing.xxl * 3

/** 弹幕开关胶囊高度，与圆形按钮同档。 */
private val ToggleHeight: Dp = Spacing.xxl + Spacing.xs

/**
 * 顶部右侧的动作：只剩弹幕开关。
 *
 * 选集入口底栏已经有了，顶部再放一个属于重复入口，全屏时还容易误触。
 */
@Immutable
internal class TopActions(
    val danmuEnabled: Boolean,
    val onToggleDanmu: () -> Unit,
)

/**
 * 顶部栏：渐变压暗 + 圆形返回 + 片名/集数 + 弹幕开关 + 选集。
 *
 * 是整条 55% 实底黑条，把画面切成两段；这里只用渐变压住读字所需的那一段，
 * 画面本身保持连续。按钮也由裸图标改成 36dp 圆形点击区。
 */
@Composable
internal fun PlayerTopBar(
    title: String,
    subtitle: String,
    actions: TopActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    applyInsets: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TopScrimHeight)
            .background(
                Brush.verticalGradient(
                    0f to starveilExtraColors.heroScrimEnd,
                    1f to Color.Transparent,
                ),
            ),
    ) {
        // 竖向居中：返回圆钮、片名、弹幕胶囊同一个中轴，不再各按各的 padding 顶。
        // 全屏时再让开刘海/状态栏：否则那排按钮会被时间、电量压住，点不到。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 全屏时系统栏已隐藏，这里只用固定内边距：inset 会让左右两边的留距不对称
                .padding(top = if (applyInsets) Spacing.md else Spacing.none)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeIconButton(
                icon = PhosphorIcons.Regular.ArrowLeft,
                mirrorRtl = true,
                description = "返回",
                onClick = onBack,
            )
            Spacer(Modifier.width(Spacing.md))
            TitleBlock(
                title = title,
                subtitle = subtitle,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.sm))
            DanmuToggleChip(actions = actions)
            // 选集入口只在底栏保留：顶部重复一个既占地方又容易误触
        }
    }
}

/** 两行标题：主标题吃满剩余宽度，第二行没内容时整行不占位。 */
@Composable
private fun TitleBlock(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 弹幕开关：开启时用皮肤主色，关闭时整颗降到弱化色，一眼能看出当前状态。 */
@Composable
private fun DanmuToggleChip(actions: TopActions, modifier: Modifier = Modifier) {
    val tint = if (actions.danmuEnabled) {
        MaterialTheme.colorScheme.primary
    } else {
        starveilExtraColors.textFaint
    }
    Row(
        modifier = modifier
            .height(ToggleHeight)
            .clip(AppShape.full)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = CHROME_GLASS_ALPHA))
            .clickable(onClick = actions.onToggleDanmu)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (actions.danmuEnabled) {
                PhosphorIcons.Regular.Subtitles
            } else {
                PhosphorIcons.Regular.SubtitlesSlash
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(IconSize.sm),
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = if (actions.danmuEnabled) "弹幕开" else "弹幕关",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
        )
    }
}
