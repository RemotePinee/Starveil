package com.starveil.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * 按 [LayoutDirection] 左右镜像。
 *
 * Phosphor 没有 Material 那套 `Icons.AutoMirrored`：图标就是普通 `ImageVector`，
 * builder 的 autoMirror 未置位，RTL 下不会自己翻。方向性图标（返回箭头）要走这里
 * 手动翻一次，行为与原先的 `Icons.AutoMirrored.Rounded.ArrowBack` 对齐。
 */
@Composable
internal fun Modifier.mirrorInRtl(): Modifier =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        scale(scaleX = -1f, scaleY = 1f)
    } else {
        this
    }
