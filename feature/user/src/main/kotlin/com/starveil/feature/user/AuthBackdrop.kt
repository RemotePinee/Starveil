package com.starveil.feature.user

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.starveil.core.designsystem.starveilExtraColors

/**
 * 登录 / 注册 / 强制登录共用的背景。
 *
 * 不用「我的」页那层彩雾：彩雾靠随机颗粒出质感，铺满整屏时颗粒会盖住文字边缘，
 * 在登录这种"只有字和按钮"的页面上显得脏。这里换成纯色底 + 顶部一层极淡的品牌色光，
 * 只有明度变化、没有噪点——同样有层次，但不抢内容。
 */
@Composable
internal fun Modifier.authBackdrop(): Modifier {
    val base = MaterialTheme.colorScheme.surface
    val accent = starveilExtraColors.accent
    return drawBehind {
        drawRect(color = base)
        drawRect(
            brush = Brush.verticalGradient(
                0f to accent.copy(alpha = 0.16f),
                0.42f to accent.copy(alpha = 0.04f),
                1f to Color.Transparent,
            ),
            size = size,
        )
    }
}
