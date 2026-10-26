package com.starveil.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 页头：主标题 + LIVE 标签 + 状态副标题 + 刷新。
 *
 * 刷新放在页头（不是每个区块各放一个），刷新时用一条细进度条表示，
 * 列表内容不闪回骨架屏。
 */
@Composable
internal fun LiveHeader(
    subtitle: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.pageHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TitleRow()
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = starveilExtraColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
            IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                Icon(
                    imageVector = PhosphorIcons.Regular.ArrowsClockwise,
                    contentDescription = "刷新直播列表",
                    tint = if (isRefreshing) {
                        starveilExtraColors.textFaint
                    } else {
                        starveilExtraColors.textMuted
                    },
                    modifier = Modifier.size(IconSize.md),
                )
            }
        }
        // 进度条始终占位，只用透明度控制显隐：
        // 条件渲染会让它出现时把下面整块内容往下推，消失再弹回来（看着像页面在跳）
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm)
                .alpha(if (isRefreshing) 1f else 0f),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** 主标题左侧一条主色竖条，作为整页唯一的高亮起点。 */
@Composable
private fun TitleRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .width(Spacing.xs)
                .height(Spacing.xl)
                .background(MaterialTheme.colorScheme.primary, AppShape.full),
        )
        Text(
            text = "直播",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        LiveBadge()
    }
}
