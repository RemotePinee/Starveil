package com.starveil.feature.player

import android.os.SystemClock
import com.starveil.core.common.AppResult
import com.starveil.core.model.Danmu
import com.starveil.core.model.PlayStream
import com.starveil.core.model.TrySeeAccess
import kotlinx.coroutines.CoroutineScope
import com.starveil.core.model.DanmuTarget
/** 一条待绘制的弹幕：文本 + 轨道 + 绝对时间轴位置（毫秒，与播放位置同一口径）。 */
data class DanmuItem(
    val text: String,
    val lane: Int = 0,
    val atTimeMs: Long = 0L,
    /**
     * 排轨优先级：0 = 本站用户弹幕（CMS），1 = 解析站官方弹幕。
     *
     * 用户弹幕大多是自己发的、要看得到，所以先占轨道；官方弹幕量大，只填剩余空位，
     * 不能把 CMS 那批挤掉。
     */
    val priority: Int = PRIORITY_USER,
    /** 弹幕颜色（`#RRGGBB` / `RRGGBB`）；null 用默认白。发送时选的颜色必须原样带到这里。 */
    val colorHex: String? = null,
) {
    companion object {
        const val PRIORITY_USER = 0
        const val PRIORITY_OFFICIAL = 1
    }
}

/**
 * 单条解析线路的探测。
 *
 * 是隐藏 WebView 嗅探，本期不迁 WebView
 * （开了文件访问与证书放行，见 docs/security.md 第 1 节），只保留可注入的接口，
 * 真实嗅探实现由 :app 侧后续提供。
 */
interface LineProbe {
    /**
     * 返回一条流；B 站双流会带 [PlayStream.audioUrl]，单流平台为 null。
     * 返回 null 表示这条线路失败，交给状态机换线。
     */
    suspend fun probe(targetIndex: Int, target: String, vodUrl: String): PlayStream?
}

/**
 * 观看进度上报出口。
 *
 * 接口定义在本模块（不新增网络接口），实现放在 :app 的 DI 容器里调仓库，
 * feature 模块不允许直连 HTTP（AGENTS.md 第 4 节）。
 */
interface ProgressSink {

    /** 观看时长（积分 / 任务口径），[seconds] 是本次新增秒数。 */
    suspend fun reportWatchedSeconds(
        sourceIndex: Int,
        episodeIndex: Int,
        seconds: Long,
    ): AppResult<Unit>

    /**
     * 保存播放位置（秒），用于「观看记录」与「断点续播」。
     *
     * 默认空实现：观看时长与位置是两个后端表，测试 fake 不关心位置时不用跟着补。
     */
    suspend fun savePosition(
        vodId: Int,
        sourceIndex: Int,
        episodeIndex: Int,
        positionSec: Int,
    ): AppResult<Unit> = AppResult.Success(Unit)
}

/**
 * 弹幕数据出口。
 *
 * 接口定义在本模块，:app 用 :core:data 的实现注入；feature 不直连仓库实现
 * （AGENTS.md 第 1 节依赖方向）。
 *
 * 三个坐标与 CMS 弹幕表一致：`vod_id` / `vod_sid`(播放源下标) / `vod_nid`(集下标)。
 * 这样 App 发的弹幕直接进 CMS 的弹幕库，网页播放器和后台「弹幕管理」看到的是同一份数据。
 */
interface DanmuSource {

    /** 拉取 [atTimeMs] 之后（含）的弹幕。 */
    suspend fun load(target: DanmuTarget, atTimeMs: Long): AppResult<List<Danmu>>

    /**
     * 拉官方弹幕（解析站下发的 `danmu` 地址，qilin-danmu）。
     *
     * 与 [load] 是两套数据：这里是平台官方弹幕，[load] 是本站用户弹幕。
     * 默认空实现，避免每个测试 fake 都要跟着补。
     */
    suspend fun fetchOfficial(url: String): AppResult<List<Danmu>> = AppResult.Success(emptyList())

    /** 发送一条弹幕（写入 CMS 弹幕库）。 */
    suspend fun send(
        target: DanmuTarget,
        content: String,
        atTimeMs: Long,
        colorHex: String,
    ): AppResult<Unit>
}

/** 时钟抽象：8 秒超时与 30 秒上报周期都按它算，测试注入假时钟。 */
fun interface PlayerClock {
    fun nowMs(): Long
}

/** 生产用时钟：单调时钟，避免系统时间被改写后超时判定跳变。 */
object SystemPlayerClock : PlayerClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * ViewModel 依赖。
 *
 * [scope] 为 null 时用 viewModelScope；测试注入 TestScope 以便控制虚拟时间。
 * [trySeeAccess] 来自服务端下发（`TrySeeRules.access`）；[trySeeMinutes] 已废弃
 * （服务端不再下发有效秒数），只为兼容旧调用保留，不参与任何文案。
 */
data class PlayerDeps(
    val probe: LineProbe,
    val progressSink: ProgressSink,
    val clock: PlayerClock = SystemPlayerClock,
    val scope: CoroutineScope? = null,
    val trySeeAccess: TrySeeAccess = TrySeeAccess.FREE,
    val trySeeMinutes: Int = 0,
    val danmuSource: DanmuSource? = null,
)

/**
 * 一次性事件。走 Channel，不放 UiState（AGENTS.md 第 5 节）。
 */
sealed interface PlayerEvent {

    /** 试看闸门命中，UI 需要弹覆盖层。 */
    data class TrySeeBlocked(val access: TrySeeAccess) : PlayerEvent

    /** 用户点了「开通会员」。 */
    data object RequestUpgrade : PlayerEvent

    /** 用户点了「积分兑换」（旧「购买本集」）。 */
    data object RequestPurchase : PlayerEvent

    /** 播放器层面的错误（解码/网络），与线路解析失败区分开。 */
    data class PlaybackFailed(val message: String) : PlayerEvent

    /** 弹幕发送失败；弹幕问题不改变播放状态，只提示一次。 */
    /** 弹幕发送失败；弹幕问题不改变播放状态，只提示一次。 */
    data class DanmuFailed(val message: String) : PlayerEvent

    /** 播完自动接下一集：UI 提示"正在播下一集"，避免用户只看到一个转圈。 */
    data class AutoPlayNext(val episodeTitle: String) : PlayerEvent
}
