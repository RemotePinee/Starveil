package com.starveil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/** 控制层按钮直径：36dp，比底部安全区小一档，不跟画面抢注意力。 */
internal val ChromeButtonSize: Dp = Spacing.xxl + Spacing.xs

/** 半透明底：「压暗画面但不遮画面」的统一口径，顶栏与底栏共用。 */
internal const val CHROME_GLASS_ALPHA = 0.32f

/**
 * 图标按钮：顶栏用半透明圆底，底栏用无底色的大点击区。
 *
 * 是裸 [Icon] + `clickable`，点击区只有图标本身大小（20dp），手指稍偏就落空；
 * 统一成 36dp 的整块点击区。底栏不画圆底——底栏已经压在 92% 的渐隐上，
 * 再叠五六个圆底会把一条控制行画成"按钮矩阵"，B 站的做法也是只留图标。
 */
@Composable
internal fun ChromeIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    glass: Boolean = true,
    /** 方向性图标（返回、选集…）在 RTL 下需要镜像；只翻图标，点击区保持原样。 */
    mirrorRtl: Boolean = false,
) {
    val background = if (glass) {
        Modifier.background(MaterialTheme.colorScheme.scrim.copy(alpha = CHROME_GLASS_ALPHA))
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(ChromeButtonSize)
            // clip 必须排在 clickable 之前：按下时的水波纹画在 clickable 节点内，
            // 不裁圆就是一块 36dp 的方形半透明底，和圆图标对不上。
            .clip(AppShape.full)
            .then(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val iconMirror = if (mirrorRtl) Modifier.mirrorIconInRtl() else Modifier
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(IconSize.item).then(iconMirror),
        )
    }
}
