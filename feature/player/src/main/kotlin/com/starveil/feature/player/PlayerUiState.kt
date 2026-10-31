package com.starveil.feature.player

import androidx.compose.runtime.Immutable
import com.starveil.core.model.PlaybackPhase
import com.starveil.core.model.TrySeeAccess

/**
 * 清晰度/线路选项。
 *
 * 「播放源」与「清晰度」在本模块是同一条数据：Vod.playSources 的下标。
 */
@Immutable
data class QualityOption(val sourceIndex: Int, val label: String)

/**
 * 播放页 UI 状态。不可变，经 StateFlow 暴露（AGENTS.md 第 5 节）。
 *
 * 线路/集数只从 [com.starveil.core.model.PlaybackSession] 复制过来用于展示，
 * 真正的索引与失败集合都在会话里，这里不允许出现第二份可变来源。
 */
@Immutable
data class PlayerUiState(
    val phase: PlaybackPhase = PlaybackPhase.Idle,
    val sourceIndex: Int = 0,
    val episodeIndex: Int = 0,
    val sourceName: String = "",
    val episodeTitle: String = "",
    val episodes: List<String> = emptyList(),
    val qualities: List<QualityOption> = emptyList(),
    val trySeeNotice: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long = 0L,
    val speed: Float = PlayerSpeeds.DEFAULT,
    val isPlaying: Boolean = false,
    /** 仅由用户主动暂停时允许展示暂停贴片；生命周期暂停不算广告曝光。 */
    val isPauseAdEligible: Boolean = false,
    val isFullscreen: Boolean = false,
    val isBuffering: Boolean = false,
    /**
     * 待执行的定位（断点续播）。
     *
     * 起播后交给播放器 seek 一次，成功再清零：引擎要等 prepare 完成才能 seek，
     * 只改播放位置是不够的（位置单独一条流，见 `PlayerViewModel.position`）。
     */
    val pendingSeekMs: Long = 0L,
) {

    /** 当前播放地址；只有 [PlaybackPhase.Playing] 才有值。 */
    val url: String? get() = (phase as? PlaybackPhase.Playing)?.url

    /** 双流的音轨地址；单流平台为 null（B 站 DASH 直链才有）。 */
    val audioUrl: String? get() = (phase as? PlaybackPhase.Playing)?.audioUrl

    /** 命中 issethead 时的请求头（ParseRules.resolveHeaders），否则为空表。 */
    val headers: Map<String, String> get() = (phase as? PlaybackPhase.Playing)?.headers.orEmpty()

    /** 正在出流量的线路下标，用于「换线路」时记录失败。 */
    val lineIndex: Int? get() = (phase as? PlaybackPhase.Playing)?.targetIndex

    /** 试看拦截时的权限类型，决定覆盖层按钮。 */
    val blockedAccess: TrySeeAccess? get() = (phase as? PlaybackPhase.Blocked)?.access

    /** 是否需要展示试看覆盖层。 */
    val isBlocked: Boolean get() = phase is PlaybackPhase.Blocked
}

/**
 * 可选倍速（0.5–3.0）。
 *
 * 只在这几档之间循环，避免出现 1.07x 这种无法复现的档位。
 */
object PlayerSpeeds {

    const val DEFAULT = 1.0f

    val STEPS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    /** 下一档；当前值不在档位表里时回到第一档。 */
    fun next(current: Float): Float {
        val index = STEPS.indexOf(current)
        return STEPS[(index + 1) % STEPS.size]
    }
}
