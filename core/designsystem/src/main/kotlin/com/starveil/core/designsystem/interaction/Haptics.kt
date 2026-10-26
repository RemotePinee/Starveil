package com.starveil.core.designsystem.interaction

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/**
 * 取一个绑定当前 View 的触感入口，用法：`val haptics = rememberHaptics()`。
 *
 * 直接用 [HapticFeedbackConstants] 而不是 Compose 的 `HapticFeedbackType`：
 * 后者只有 LongPress/TextHandleMove 两档，做不出"确认"和"失败"的区别；
 * 按系统版本降级（CONFIRM/REJECT 需要 API 30+），低版本退回 LONG_PRESS。
 */
@Composable
fun rememberHaptics(): (HapticKind) -> Unit {
    val view = LocalView.current
    return { kind -> view.performHaptics(kind) }
}

private fun View.performHaptics(kind: HapticKind) {
    val supportsRich = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val constant = when (kind) {
        HapticKind.Tick -> HapticFeedbackConstants.CLOCK_TICK
        HapticKind.Confirm -> if (supportsRich) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }

        HapticKind.Reject -> if (supportsRich) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
    }
    performHapticFeedback(constant)
}
