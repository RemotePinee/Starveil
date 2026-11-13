package com.starveil.core.model

/**
 * 投屏会话。UI 只读它，不直接碰协议。
 *
 * [positionMs] / [durationMs] 来自电视端的 `GetPositionInfo`，与本地播放器无关：
 * 退出投屏时以它为准回到本地同一进度（见 `CastRepository.release` 的约定）。
 */
data class CastSession(
    val device: CastDevice,
    val state: CastPlaybackState = CastPlaybackState.Idle,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * 投屏整体状态。用封闭集合而不是一堆布尔：页面只渲染一种形态，
 * "正在搜索 / 搜到了哪些 / 连着谁 / 为什么失败"不可能同时为真。
 */
sealed interface CastUiStatus {

    /** 没在投，也没在搜。 */
    data object Idle : CastUiStatus

    /** 正在 SSDP 搜索（最多 [CAST_SEARCH_TIMEOUT_MS]）。 */
    data object Searching : CastUiStatus

    /** 搜完了，[devices] 为空表示同一网络里没有可投设备。 */
    data class Devices(val devices: List<CastDevice>) : CastUiStatus

    /** 已连上并投出去。 */
    data class Connected(val session: CastSession) : CastUiStatus

    /** 失败原因已经是可以直接显示的中文，不把异常抛给 UI。 */
    data class Failed(val reason: String) : CastUiStatus
}

/** 搜索窗口：够所有电视回包，又不会让用户盯着转圈。 */
const val CAST_SEARCH_TIMEOUT_MS = 4_000L
