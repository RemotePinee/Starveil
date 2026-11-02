package com.starveil.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.token.Spacing

/**
 * 宿主底栏遮住的内容高度，供滚动页面把最后一条留出来。
 *
 * dock 是浮在内容之上的，列表滚到底时最后一行会被它盖住；[AppScaffold] 把底栏实际占的
 * 高度从这里传下去，页面自己加进 `contentPadding` 就行，不必各自去猜底栏多高
 * （图标形态 64dp、纯文字形态 44dp，还要叠系统导航栏的 inset）。
 *
 * 默认 [Spacing.none]：全屏播放、二级页这类没有 dock 的宿主不做任何留白。
 */
val LocalDockInset = staticCompositionLocalOf<Dp> { Spacing.none }
