package com.starveil.feature.player

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.Danmu
import com.starveil.core.model.DanmuTarget

/** 一次弹幕拉取：坐标与当时的窗口起点。整集只拉一次，所以 atTimeMs 通常是 0。 */
data class DanmuLoadCall(val target: DanmuTarget, val atTimeMs: Long)

/** 一次弹幕发送。 */
data class DanmuSendCall(val target: DanmuTarget, val content: String, val atTimeMs: Long)

/**
 * 弹幕出口替身：按窗口起点返回预设弹幕，未配置的窗口按"拉空"处理。
 *
 * 只用于 JVM 测试，不碰网络；[loadFailure] 用来验证"弹幕失败不影响播放"。
 */
class FakeDanmuSource(private val windows: Map<Long, List<Danmu>> = emptyMap()) : DanmuSource {

    val loads = mutableListOf<DanmuLoadCall>()

    val sends = mutableListOf<DanmuSendCall>()

    /** 非 null 时所有拉取都返回该错误。 */
    var loadFailure: AppError? = null

    var sendResult: AppResult<Unit> = AppResult.Success(Unit)

    /** 官方弹幕（解析站 danmu 地址）返回的内容，按 url 记录请求次数。 */
    var officialItems: List<Danmu> = emptyList()

    val officialRequests = mutableListOf<String>()

    override suspend fun load(target: DanmuTarget, atTimeMs: Long): AppResult<List<Danmu>> {
        loads += DanmuLoadCall(target = target, atTimeMs = atTimeMs)
        val failure = loadFailure
        return if (failure == null) {
            AppResult.Success(windows[atTimeMs].orEmpty())
        } else {
            AppResult.Failure(failure)
        }
    }

    override suspend fun fetchOfficial(url: String): AppResult<List<Danmu>> {
        officialRequests += url
        return AppResult.Success(officialItems)
    }

    override suspend fun send(
        target: DanmuTarget,
        content: String,
        atTimeMs: Long,
        colorHex: String,
    ): AppResult<Unit> {
        sends += DanmuSendCall(target = target, content = content, atTimeMs = atTimeMs)
        return sendResult
    }
}

/** 测试用弹幕；status 取后端的"正常"值，颜色留空。 */
fun fakeDanmu(id: Int, atTimeMs: Long, content: String = "弹幕$id"): Danmu = Danmu(
    id = id,
    content = content,
    userId = 0,
    atTimeMs = atTimeMs,
    createdAt = 0,
    status = 1,
    likeCount = 0,
    color = null,
)
