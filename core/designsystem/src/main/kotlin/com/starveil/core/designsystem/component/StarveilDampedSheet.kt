package com.starveil.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

/** 评论与选集共用：安全区硬边界、渐进拖拽阻力、三停靠点弹簧归位。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StarveilDampedSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable DampedSheetScope.() -> Unit,
) {
    val state = remember { DampedSheetState() }
    val scope = rememberCoroutineScope()
    val latestDismiss = rememberUpdatedState(onDismissRequest)
    val callbacks = remember(state, scope) { SheetCallbacks(state, scope) { latestDismiss.value() } }
    Dialog(
        onDismissRequest = callbacks::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SheetWindowEffects()
        val density = LocalDensity.current
        val top = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
        val bottom = WindowInsets.ime.union(WindowInsets.navigationBars).getBottom(density)
        BoxWithConstraints(modifier.fillMaxSize()) {
            val geometry = SheetGeometry(
                windowHeight = constraints.maxHeight,
                top = top,
                bottom = bottom,
                handle = with(density) { Spacing.xxl.roundToPx() },
            )
            val boundsReady = remember { mutableStateOf(false) }
            LaunchedEffect(geometry) {
                if (geometry.height > 0) {
                    if (state.updateBounds(geometry.height, geometry.windowHeight)) {
                        callbacks.move(SheetAnchor.Half)
                    }
                    boundsReady.value = true
                }
            }
            if (boundsReady.value && geometry.height > 0) {
                SheetScrim(state, callbacks)
                SheetSurface(state, geometry, callbacks, content)
            }
        }
    }
}

/** 只保留随面板偏移绘制的遮罩，不叠加系统 Dialog 的 dim 和退场动画。 */
@Composable
private fun SheetWindowEffects() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0f)
        window?.setWindowAnimations(0)
        onDispose { /* Dialog 销毁时一并回收该窗口，不修改 Activity 窗口。 */ }
    }
}

private data class SheetGeometry(val windowHeight: Int, val top: Int, val bottom: Int, val handle: Int) {
    val height: Int get() = (windowHeight - top - bottom).coerceAtLeast(0)
    val bodyHeight: Int get() = (height - handle).coerceAtLeast(0)
}

private class SheetCallbacks(
    val state: DampedSheetState,
    val scope: CoroutineScope,
    val onDismiss: () -> Unit,
) {
    fun dismiss() = move(SheetAnchor.Hidden)
    fun move(anchor: SheetAnchor) = state.animateTo(scope, anchor, onDismiss = onDismiss)
    fun settle(velocity: Float) = state.settle(scope, velocity, onDismiss)
}

@Composable
private fun SheetScrim(state: DampedSheetState, callbacks: SheetCallbacks) {
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val fraction = 1f - state.offsetPx / state.maxHeightPx.coerceAtLeast(1)
                alpha = Motion.SheetScrimAlpha * fraction.coerceIn(0f, 1f)
            }
            .background(MaterialTheme.colorScheme.scrim)
            .pointerInput(callbacks) { detectTapGestures { callbacks.dismiss() } },
    )
}

@Composable
private fun SheetSurface(
    state: DampedSheetState,
    geometry: SheetGeometry,
    callbacks: SheetCallbacks,
    content: @Composable DampedSheetScope.() -> Unit,
) {
    val density = LocalDensity.current
    val connection = remember(callbacks) {
        DampedSheetNestedScrollConnection(state, callbacks.scope, callbacks.onDismiss)
    }
    Surface(
        modifier = Modifier.fillMaxWidth()
            .height(with(density) { geometry.height.toDp() })
            .offset { IntOffset(0, geometry.top + state.offsetPx.roundToInt()) }
            .nestedScroll(connection)
            .draggable(
                state = state.draggableState,
                orientation = Orientation.Vertical,
                onDragStarted = { state.stop() },
                onDragStopped = { callbacks.settle(it) },
            )
            .pointerInput(Unit) { detectTapGestures { /* 空白处吃掉点击，保留子项点击。 */ } },
        shape = AppShape.sheetTop,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            SheetHandle(callbacks)
            Box(Modifier.fillMaxWidth().height(with(density) { geometry.bodyHeight.toDp() })) {
                val sheetScope = remember(state, density, geometry.bodyHeight) {
                    SheetScopeImpl(state, density, geometry.bodyHeight)
                }
                sheetScope.content()
            }
        }
    }
}

@Composable
private fun SheetHandle(callbacks: SheetCallbacks) {
    Box(
        Modifier.fillMaxWidth().height(Spacing.xxl)
            .semantics {
                expand { callbacks.move(SheetAnchor.Expanded); true }
                collapse { callbacks.move(SheetAnchor.Half); true }
                dismiss { callbacks.dismiss(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(Spacing.xxl, Spacing.xs).clip(AppShape.full)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

private class SheetScopeImpl(
    private val state: DampedSheetState,
    private val density: Density,
    private val bodyHeight: Int,
) : DampedSheetScope {
    override val maxHeight: Dp get() = with(density) { bodyHeight.toDp() }
    override val visibleHeight: Dp
        get() = with(density) { sheetVisibleHeight(bodyHeight, state.offsetPx).toDp() }
    override val offsetPx: Float get() = state.offsetPx
    override val windowHeightPx: Int get() = state.windowHeightPx
}
