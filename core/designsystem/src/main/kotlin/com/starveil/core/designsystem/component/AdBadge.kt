package com.starveil.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing

/**
 * 广告角标：「广告」两个字，压在素材左上角。
 *
 * 只画文字与底色，不带定位——放在哪个角由调用方决定，避免每个页面各写一套 offset。
 * 颜色走扩展语义色，五套皮肤下都可读。
 */
@Composable
fun AdBadge(
    modifier: Modifier = Modifier,
    label: String = "广告",
) {
    val colors = starveilExtraColors
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = colors.badge,
        modifier = modifier
            .clip(AppShape.extraSmall)
            .background(colors.chipBackground)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
    )
}
