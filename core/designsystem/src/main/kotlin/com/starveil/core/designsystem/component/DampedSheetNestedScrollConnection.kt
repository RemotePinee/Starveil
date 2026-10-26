package com.starveil.core.designsystem.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope

/** 上滑先展开，展开后交给列表；下滑只接收列表到顶后剩余的距离。 */
internal class DampedSheetNestedScrollConnection(
    private val state: DampedSheetState,
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit,
) : NestedScrollConnection {
    private var sheetDragged = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val consume = source == NestedScrollSource.UserInput && available.y < 0f && state.offsetPx > 0f
        if (consume) {
            sheetDragged = true
            state.dragBy(available.y)
        }
        return if (consume) Offset(0f, available.y) else Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val consume = source == NestedScrollSource.UserInput && available.y > 0f
        if (consume) {
            sheetDragged = true
            state.dragBy(available.y)
        }
        return if (consume) Offset(0f, available.y) else Offset.Zero
    }


    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!sheetDragged || state.offsetPx <= 0f) return Velocity.Zero
        sheetDragged = false
        state.settle(scope, available.y, onDismiss)
        return Velocity(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!sheetDragged) return Velocity.Zero
        sheetDragged = false
        state.settle(scope, available.y, onDismiss)
        return Velocity(0f, available.y)
    }
}
