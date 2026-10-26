package com.starveil.feature.player

import androidx.compose.runtime.Immutable
import com.starveil.core.common.AppError
import com.starveil.core.model.TrySeeAccess

/**
 * 竖屏播放的一集。
 *
 * [index] 是 pager 页号（0 起），[number] 是后端 nid（1 起），
 * 两者不可混用——与 [com.starveil.core.model.PlayUrl] 的同一约定。
 */
@Immutable
data class VerticalEpisode(
    val index: Int,
    val number: Int,
    val title: String,
    val url: String,
)

/**
 * 竖屏播放状态。不可变，经 StateFlow 暴露（AGENTS.md 第 5 节）。
 *
 * 与 [PlayerUiState] 的差别：短剧是"单源多集直链"，没有线路探测与换源，
 * 所以这里只有一条地址；播放时序字段（位置/时长/是否在播）与横屏页同口径。
 */
@Immutable
data class VerticalPlayUiState(
    val title: String = "",
    val cover: String? = null,
    val episodes: List<VerticalEpisode> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val playingUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val audioUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 试看到期后的权限类型；null 表示没被拦。解锁流程归计划 §5 P4，这里只负责停下。 */
    val blockedAccess: TrySeeAccess? = null,
    val description: String = "",
    val category: String = "",
    val typeId: Int = 0,
    /** 同地址重试的媒体修订；普通 seek 不重建媒体源或清空缓冲。 */
    val playbackRevision: Long = 0L,
    val seekRevision: Long = 0L,
    /** 列表已有的集数只供文案占位，不代表可播放或已通过权限校验。 */
    val episodeCountHint: Int = 0,
) {

    /** 当前页。列表为空时为 null，调用方不必再判下标。 */
    val current: VerticalEpisode? get() = episodes.getOrNull(currentIndex)

    val episodeCount: Int get() = episodes.size.takeIf { it > 0 } ?: episodeCountHint.coerceAtLeast(0)

    /** 集数文案：`第 3 集 / 共 80 集`。 */
    val episodeCountLabel: String
        get() = "第 ${current?.number ?: 0} 集 / 共 ${episodes.size} 集"

    /** 试看覆盖层是否可见。 */
    val isBlocked: Boolean get() = blockedAccess != null
}

/** 只表达实际播放阶段，不按计时器伪造进度或自动递进。 */
internal enum class VerticalLoadingPhase(val title: String) {
    Details("正在载入剧集"),
    Access("正在确认播放权限"),
    Video("正在连接视频"),
    Frame("正在等待画面"),
    Buffering("正在缓冲，稍后继续"),
    Ready("画面已就绪"),
}

/** 后端没给可播地址（或给了脏数据）时的兜底文案，与解析失败的提示分开。 */
internal const val MSG_NO_PLAY_URL = "该集暂时没有可播放地址"

internal const val MSG_NO_EPISODE = "这部剧还没有可播放的剧集"

/** 加载失败文案。口径同播放页 `PlayerEntry`：业务错误优先用后端 msg。 */
internal fun verticalErrorMessage(error: AppError): String = when (error) {
    is AppError.Network -> "网络异常，无法加载剧集"
    is AppError.Business -> error.message.ifBlank { "剧集不可用" }
    is AppError.Http -> "服务异常（${error.code}）"
    is AppError.Serialization -> "数据解析失败"
    is AppError.Unknown -> "未知错误"
}

/** 试看覆盖层文案。文案集中在状态层拼，在 Activity 里散着写。 */
internal fun trySeeNoticeOf(access: TrySeeAccess): String = when (access) {
    TrySeeAccess.FREE -> ""
    TrySeeAccess.NEED_VIP -> MSG_TRY_SEE_VIP
    TrySeeAccess.NEED_PAY -> MSG_TRY_SEE_PAY
}

/**
 * 试看覆盖层文案（按能力位组合）。
 *
 * 会员与积分都可用时要说清两个出口，不能只写"开通会员"——
 * 用户会以为必须买会员才能继续看。
 */
internal fun trySeeNoticeOf(access: TrySeeAccess, unlock: UnlockOptions): String = when {
    unlock.canUpgrade && unlock.canRedeemPoints -> MSG_TRY_SEE_BOTH
    unlock.canUpgrade -> MSG_TRY_SEE_VIP
    unlock.canRedeemPoints -> MSG_TRY_SEE_PAY
    else -> trySeeNoticeOf(access)
}
