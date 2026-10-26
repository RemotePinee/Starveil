package com.starveil.core.designsystem.token

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形状令牌。海报统一 medium，弹层统一 large，不允许页面自写 RoundedCornerShape。
 */
object AppShape {
    val extraSmall = RoundedCornerShape(4.dp)
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val extraLarge = RoundedCornerShape(24.dp)
    val full = RoundedCornerShape(percent = 50)

    /** 底部弹层：只有上沿圆角，贴底那条边压在屏幕边缘上，不需要圆。 */
    val sheetTop = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

    /** 内容面板上沿圆角（32dp）：面板压在彩雾带上，弧外侧露出下面的雾。 */
    val sheetTopLarge = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
}

internal val StarveilShapes = Shapes(
    extraSmall = AppShape.extraSmall,
    small = AppShape.small,
    medium = AppShape.medium,
    large = AppShape.large,
    extraLarge = AppShape.extraLarge,
)
