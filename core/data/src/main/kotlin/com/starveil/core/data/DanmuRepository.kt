package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.Danmu
import com.starveil.core.model.DanmuTarget
import com.starveil.core.network.CmsDanmakuApi
import com.starveil.core.network.dto.CmsDanmakuRowDto
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/**
 * 弹幕收发：直接读写**苹果CMS 内置弹幕库**（`/api.php/danmaku/...`）。
 *
 * 这样网页播放器、后台「弹幕管理」和 App 用的是同一份数据：用户在 App 发的弹幕后台
 * 能看到、能删；后台删掉的 App 也拉不到。此前写插件自建表 `cuican_danmu`，
 * 后台弹幕管理永远空着。
 *
 * 坐标是 `vod_id / sid(播放源下标) / nid(集下标)`，与 CMS 弹幕表字段一一对应。
 * 该接口成功码是 `code == 1`（不是 200），失败时 `msg` 可直接展示给用户。
 */
interface DanmuRepository {

    suspend fun list(target: DanmuTarget): AppResult<List<Danmu>>

    suspend fun send(
        target: DanmuTarget,
        content: String,
        atTimeMs: Long,
        colorHex: String,
    ): AppResult<Unit>
}

class DefaultDanmuRepository(private val api: CmsDanmakuApi) : DanmuRepository {

    override suspend fun list(target: DanmuTarget): AppResult<List<Danmu>> =
        guard {
            val response = api.list(vodId = target.vodId, sid = target.sid, nid = target.nid)
            if (response.code != SUCCESS_CODE) {
                AppResult.Failure(AppError.Business(response.code, response.msg))
            } else {
                AppResult.Success(response.info?.rows.orEmpty().map { it.toDomain() })
            }
        }

    override suspend fun send(
        target: DanmuTarget,
        content: String,
        atTimeMs: Long,
        colorHex: String,
    ): AppResult<Unit> = guard {
        val response = api.send(
            mapOf(
                "vod_id" to target.vodId.toString(),
                "sid" to target.sid.toString(),
                "nid" to target.nid.toString(),
                // CMS 的 time 是秒（可带小数），对外统一毫秒
                "time" to String.format(java.util.Locale.ROOT, "%.3f", atTimeMs.coerceAtLeast(0L) / 1000f),
                "text" to content,
                "type" to "0",
                "color" to normalizeColor(colorHex),
            ),
        )
        if (response.code != SUCCESS_CODE) {
            AppResult.Failure(AppError.Business(response.code, response.msg))
        } else {
            AppResult.Success(Unit)
        }
    }

    /** 接口不走 Retrofit 的泛型壳，这里自己收敛异常，口径与 `apiCall` 保持一致。 */
    private suspend fun <T> guard(block: suspend () -> AppResult<T>): AppResult<T> = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        AppResult.Failure(AppError.Network(io))
    } catch (http: HttpException) {
        AppResult.Failure(AppError.Http(http.code(), http.message().orEmpty()))
    } catch (other: Exception) {
        AppResult.Failure(AppError.Unknown(other))
    }

    /** CMS 只认 `#RRGGBB`，客户端传的是不带 `#` 的主题色。 */
    private fun normalizeColor(colorHex: String): String {
        val raw = colorHex.ifBlank { DEFAULT_COLOR }
        return if (raw.startsWith("#")) raw else "#$raw"
    }

    private companion object {
        /** CMS 弹幕接口的成功码。 */
        const val SUCCESS_CODE = 1

        const val DEFAULT_COLOR = "FFFFFF"
    }
}

/** 行 → 领域模型：秒转毫秒，其余字段直传。 */
private fun CmsDanmakuRowDto.toDomain(): Danmu = Danmu(
    id = id,
    content = text,
    userId = 0,
    atTimeMs = (time * 1000).toLong().coerceAtLeast(0L),
    createdAt = sendTime,
    status = 1,
    likeCount = 0,
    color = color,
)
