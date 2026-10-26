package com.starveil.feature.player

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.DanmuTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** 弹幕发送失败时的兜底文案：后端没给原因才用它。 */
private const val DANMU_FAILED = "弹幕发送失败，请稍后再试"

/**
 * 播放页的弹幕门面。
 *
 * ViewModel 只留三行调用（上屏、发送、换集重置），把 [DanmuController] 的细节与
 * 错误文案收在这里——PlayerViewModel 的类行数有上限（detekt LargeClass 200 行）。
 */
internal class PlayerDanmuDispatcher(
    source: DanmuSource?,
    scope: CoroutineScope,
    videoRef: () -> DanmuTarget,
    clock: PlayerClock = SystemPlayerClock,
    private val emit: (PlayerEvent) -> Unit,
) {

    private val controller = DanmuController(
        source = source,
        scope = scope,
        videoRef = videoRef,
        clock = clock,
    )

    /** 整集弹幕，交给 DanmuLayer 按播放位置绘制。 */
    val items: StateFlow<List<DanmuItem>> = controller.items

    fun onPosition() = controller.onPosition()

    fun reset() = controller.reset()

    fun ensureOfficial(url: String) = controller.ensureOfficial(url)

    /** 发送弹幕；失败把后端原话透出来（429 冷却 / 403 未登录 / 500 表缺失 能一眼区分）。 */
    fun send(content: String, atTimeMs: Long, colorHex: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        controller.send(text, atTimeMs, colorHex) { result ->
            if (result is AppResult.Failure) emit(PlayerEvent.DanmuFailed(messageOf(result.error)))
        }
    }

    private fun messageOf(error: AppError): String = when (error) {
        is AppError.Business -> error.message.ifBlank { DANMU_FAILED }
        is AppError.Network -> "网络异常，弹幕没发出去"
        is AppError.Http -> "服务异常（${error.code}），弹幕没发出去"
        else -> DANMU_FAILED
    }
}
