package com.starveil.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * 按 [LayoutDirection] 左右镜像图标，仅在 RTL 下生效。
 *
 * Phosphor 没有 Material 那套 `Icons.AutoMirrored`：图标就是普通 `ImageVector`，
 * builder 的 autoMirror 未置位，RTL 下不会自己翻。返回箭头、右向 Chevron、发送、
 * 播放列表这类方向性图标，在绘制 `Icon` 的 modifier 上追加本函数即可。
 *
 * 只加在图标本身，不要加在点击容器上：容器跟着翻会把水波纹、内边距与相邻文字
 * 一起镜像，点击区和读数都会错位。
 */
@Composable
fun Modifier.mirrorIconInRtl(): Modifier =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        scale(scaleX = -1f, scaleY = 1f)
    } else {
        this
    }
