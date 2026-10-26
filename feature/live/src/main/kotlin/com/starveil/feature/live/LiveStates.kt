package com.starveil.feature.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.CloudSlash
import com.adamglin.phosphoricons.regular.VideoCameraSlash
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/** 空态：文案来自 ViewModel，界面只说"接下来可以做什么"。 */
@Composable
internal fun LiveEmptyState(message: String, onRefresh: () -> Unit) {
    LiveStateFrame {
        Icon(
            imageVector = PhosphorIcons.Regular.VideoCameraSlash,
            contentDescription = null,
            tint = starveilExtraColors.textFaint,
            modifier = Modifier.size(IconSize.lg),
        )
        StateText(title = message, hint = "暂时没有开播的频道，稍后再来看看")
        Spacer(modifier = Modifier.height(Spacing.lg))
        QuietAction(label = "刷新", onClick = onRefresh)
    }
}

/** 错误态：文案来自 ViewModel，重试复用列表刷新。 */
@Composable
internal fun LiveErrorState(message: String, onRefresh: () -> Unit) {
    LiveStateFrame {
        Icon(
            imageVector = PhosphorIcons.Regular.CloudSlash,
            contentDescription = null,
            tint = starveilExtraColors.textFaint,
            modifier = Modifier.size(IconSize.lg),
        )
        StateText(title = message, hint = "请检查网络连接，或稍后再试")
        Spacer(modifier = Modifier.height(Spacing.lg))
        Button(onClick = onRefresh) {
            Icon(
                imageVector = PhosphorIcons.Regular.ArrowsClockwise,
                contentDescription = null,
                modifier = Modifier.size(IconSize.sm),
            )
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
    }
}

/** 空/错两态共用的居中容器，保证两种状态在视觉上同一口径。 */
@Composable
private fun LiveStateFrame(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun StateText(title: String, hint: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.md),
    )
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = starveilExtraColors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

/** 次要操作：深底胶囊，不与主操作抢注意力。 */
@Composable
private fun QuietAction(label: String, onClick: () -> Unit) {
    Surface(
        shape = AppShape.full,
        color = starveilExtraColors.chipBackground,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(
                imageVector = PhosphorIcons.Regular.ArrowsClockwise,
                contentDescription = null,
                tint = starveilExtraColors.textMuted,
                modifier = Modifier.size(IconSize.sm),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}
