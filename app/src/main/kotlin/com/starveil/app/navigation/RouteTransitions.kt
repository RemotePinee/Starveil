package com.starveil.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

/** 搜索也复用统一横滑，不再单独缩放或淡入整页。 */
internal fun searchEnter(): EnterTransition =
    pushEnter()

internal fun searchExit(): ExitTransition =
    pushExit()

internal fun searchPopEnter(): EnterTransition =
    pushPopEnter()

internal fun searchPopExit(): ExitTransition =
    pushPopExit()
