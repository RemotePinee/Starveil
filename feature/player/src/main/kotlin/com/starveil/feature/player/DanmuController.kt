package com.starveil.feature.player

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.Danmu
import com.starveil.core.model.DanmuTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 弹幕数据源与时间轴。
 *
 * 数据来源有两条，互不影响：
 * - 本站用户弹幕：苹果CMS 内置的弹幕接口，与后台「弹幕管理」同一张表；
 * - 官方弹幕：解析站下发的 `danmu` 地址（qilin-danmu）。
 *
 * 两者都是**整集一次拉全**，本类只负责合并与去重，**不做轨道排布**——
 * 排布需要真实文字像素宽度，放在绘制层（[layoutDanmu]）算，见 [DanmuLayer]。
 *
 * 时间轴严格等于弹幕自身时间，不做任何错峰或延迟：弹幕必须对上画面。
 * 弹幕是"锦上添花"：拉取失败不改播放状态、不抛事件。
 */
internal class DanmuController(
    private val source: DanmuSource?,
    private val scope: CoroutineScope,
    private val videoRef: () -> DanmuTarget,
    /** 退避计时用单调时钟；测试注入假时钟，别读系统时间。 */
    private val clock: PlayerClock = SystemPlayerClock,
) {

    private val _items = MutableStateFlow<List<DanmuItem>>(emptyList())

    /** 整集弹幕，按时间升序；轨道由绘制层分配。 */
    val items: StateFlow<List<DanmuItem>> = _items.asStateFlow()



    /** 已拉到的弹幕；键带来源，避免本地发送与后端 id 撞车。 */
    private val buffer = LinkedHashMap<String, BufferedDanmu>()

    /** 已处理（拉成功，或重试用尽）的弹幕坐标；换了影片/源/集才重新拉。 */
    private var loadedRef: DanmuTarget? = null

    /** 连续失败次数与下次允许重试的时刻；成功后一起归零，换集由 [reset] 清空。 */
    private var failedAttempts = 0
    private var retryAtMs = 0L

    private var inFlight = false
    private var requestJob: Job? = null
    private var localSeq = 0

    /** 已请求过的官方弹幕地址：同一个地址只拉一次。 */
    private var officialUrl: String? = null

    /**
     * 播放位置采样（500ms 一次）。
     *
     * CMS 弹幕接口一次给整集（没有 `at_time` 增量），所以每集只拉一次；
     * 顺带把整集列表推给 UI，位置本身由绘制层插值，不用逐帧回调。
     */
    fun onPosition() {
        if (loadedRef == videoRef()) return
        // 退避窗口内直接返回：失败后立刻重发，就是每 500ms 一份整集请求的重试风暴
        if (clock.nowMs() < retryAtMs) return
        loadAll()
    }

    /**
     * 官方弹幕（解析站 `danmu` 地址）：一次拉全量，按 url 去重。
     *
     * 拉失败静默——弹幕是锦上添花，用户弹幕那条链路不受影响。
     */
    fun ensureOfficial(url: String) {
        if (url == officialUrl) return
        officialUrl = url
        val api = source ?: return
        scope.launch {
            when (val result = api.fetchOfficial(url)) {
                is AppResult.Success -> {
                    result.value.forEach(::mergeOfficial)
                    publish()
                }

                is AppResult.Failure -> Unit
            }
        }
    }

    /** 换集/换源：弹幕坐标变了，缓冲、去重与退避状态全部作废。 */
    fun reset() {
        cancelRequest()
        buffer.clear()
        loadedRef = null
        failedAttempts = 0
        retryAtMs = 0L
        officialUrl = null
        localSeq = 0
        localSeq = 0
        _items.value = emptyList()
    }

    /**
     * 发送弹幕。成功先本地上屏再回调；失败只回调，由 ViewModel 转成一次性事件。
     */
    fun send(
        content: String,
        atTimeMs: Long,
        colorHex: String,
        onResult: (AppResult<Unit>) -> Unit,
    ) {
        val api = source
        if (api == null) {
            onResult(AppResult.Failure(AppError.Unknown(null)))
            return
        }
        scope.launch {
            val result = api.send(videoRef(), content, atTimeMs, colorHex)
            // 本地回显必须带上同一条颜色：否则自己发的弹幕先白、刷新后才变色
            if (result is AppResult.Success) showLocally(content, atTimeMs, colorHex)
            onResult(result)
        }
    }

    /** 拉整集弹幕；同一集只拉一次，失败按 1s/2s/4s 退避，次数用尽后本集不再自动重发。 */
    private fun loadAll() {
        val api = source ?: return
        val ref = videoRef()
        if (inFlight) return
        inFlight = true
        requestJob = scope.launch {
            when (val result = api.load(ref, NO_OFFSET_MS)) {
                is AppResult.Success -> onLoaded(ref, result.value)
                // 失败不静默吞掉整集：交给上面的退避，下一次采样到了再试
                is AppResult.Failure -> scheduleRetry(ref)
            }
            inFlight = false
            publish()
        }
    }

    /** 成功：合并整集、记住坐标，并把退避状态清零。 */
    private fun onLoaded(ref: DanmuTarget, danmu: List<Danmu>) {
        danmu.forEach(::merge)
        loadedRef = ref
        failedAttempts = 0
        retryAtMs = 0L
    }

    /**
     * 失败退避：间隔按 1s / 2s / 4s 翻倍，最多重试 [RETRY_DELAYS_MS] 次。
     *
     * 用尽后把坐标记成"已处理"：否则 [onPosition] 每 500ms 采样一次就会再发一份整集
     * 请求，网络差的时候等于把一个失败拖成持续轰炸。换集时 [reset] 清空，新集重新计数。
     */
    private fun scheduleRetry(ref: DanmuTarget) {
        failedAttempts += 1
        val delayMs = RETRY_DELAYS_MS.getOrNull(failedAttempts - 1)
        if (delayMs == null) {
            loadedRef = ref
            return
        }
        retryAtMs = clock.nowMs() + delayMs
    }

    private fun mergeOfficial(danmu: Danmu) {
        if (danmu.content.isBlank()) return
        val key = "o:${danmu.id}:${danmu.atTimeMs}"
        buffer[key] = BufferedDanmu(
            displayAtMs = danmu.atTimeMs.coerceAtLeast(0L),
            content = danmu.content,
            priority = DanmuItem.PRIORITY_OFFICIAL,
            colorHex = danmu.color,
        )
    }

    private fun merge(danmu: Danmu) {
        val key = "s:${danmu.id}:${danmu.atTimeMs}:${danmu.content}"
        buffer[key] = BufferedDanmu(
            displayAtMs = danmu.atTimeMs.coerceAtLeast(0L),
            content = danmu.content,
            colorHex = danmu.color,
        )
    }

    private fun showLocally(content: String, atTimeMs: Long, colorHex: String?) {
        localSeq += 1
        val key = "local:$localSeq"
        buffer[key] = BufferedDanmu(
            displayAtMs = atTimeMs.coerceAtLeast(0L),
            content = content,
            colorHex = colorHex,
        )
        publish()
    }

    /**
     * 输出整集弹幕（按时间升序）。
     *
     * 轨道排布放在绘制层做（[layoutDanmu]）：那里才能拿到真实文字像素宽度，
     * 而宽度决定一条弹幕占用轨道多久；按窗口算会让每个窗口开头重新叠一批。
     */
    private fun publish() {
        _items.value = buffer.values
            .sortedWith(compareBy({ it.displayAtMs }, { it.content }))
            .map {
                DanmuItem(
                    text = it.content,
                    lane = 0,
                    atTimeMs = it.displayAtMs,
                    priority = it.priority,
                    colorHex = it.colorHex,
                )
            }
    }

    private fun cancelRequest() {
        requestJob?.cancel()
        requestJob = null
        inFlight = false
    }

    private companion object {
        /** 整集拉取，起点固定 0。 */
        const val NO_OFFSET_MS = 0L

        /** 失败后的重试间隔：1s / 2s / 4s；数组长度就是最多重试次数。 */
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)
    }
}

/** 缓冲里的弹幕：去重键 + 文本 + 时间轴位置（与播放位置同一口径）。 */
private data class BufferedDanmu(
    val displayAtMs: Long,
    val content: String,
    /** 0 = 本站用户弹幕，1 = 官方弹幕；排轨时用户弹幕先占位。 */
    val priority: Int = DanmuItem.PRIORITY_USER,
    /** 弹幕颜色（`#RRGGBB` / `RRGGBB`）；null 用默认白。 */
    val colorHex: String? = null,
)
