package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.DanmuTarget
import com.starveil.core.network.CmsDanmakuApi
import com.starveil.core.network.dto.CmsDanmakuInfoDto
import com.starveil.core.network.dto.CmsDanmakuListDto
import com.starveil.core.network.dto.CmsDanmakuRowDto
import com.starveil.core.network.dto.CmsDanmakuSendDto
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕仓库：直接对接苹果CMS 内置弹幕库（`/api.php/danmaku/...`）。
 *
 * 重点守三件事：
 * 1. 三个坐标原样透传（`vod_id` / `sid` / `nid`），不再拼成 vodKey；
 * 2. 库里的 `danmaku_time` 是**秒**，对外必须是毫秒；
 * 3. 成功码是 `code == 1`，失败要把 `msg` 原样带到 AppError.Business。
 */
class DefaultDanmuRepositoryTest {

    @Test
    fun `list forwards target and converts seconds to millis`() = runTest {
        val api = FakeCmsDanmakuApi(
            listResponse = CmsDanmakuListDto(
                code = 1,
                msg = "ok",
                info = CmsDanmakuInfoDto(
                    total = 1,
                    rows = listOf(
                        CmsDanmakuRowDto(
                            id = 36,
                            time = 60.5,
                            color = HEX_COLOR,
                            text = "你好好啊",
                            sendTime = 1_623_476_356,
                        ),
                    ),
                ),
            ),
        )

        val result = DefaultDanmuRepository(api).list(DanmuTarget(vodId = 124, sid = 1, nid = 2))

        assertEquals(Triple(124, 1, 2), api.listArgs)
        val danmu = (result as AppResult.Success).value
        assertEquals(1, danmu.size)
        assertEquals("你好好啊", danmu.first().content)
        assertEquals(60_500L, danmu.first().atTimeMs)
    }

    @Test
    fun `send posts milliseconds as seconds and succeeds on code one`() = runTest {
        val api = FakeCmsDanmakuApi()

        val result = DefaultDanmuRepository(api)
            .send(DanmuTarget(124, 1, 2), content = "测试", atTimeMs = 12_300, colorHex = "FF7F00")

        assertTrue(result is AppResult.Success)
        val fields = requireNotNull(api.sendFields)
        assertEquals("124", fields["vod_id"])
        assertEquals("1", fields["sid"])
        assertEquals("2", fields["nid"])
        // 毫秒 → 秒，保留三位小数
        assertEquals("12.300", fields["time"])
        assertEquals("#" + HEX_COLOR, fields["color"])
    }

    @Test
    fun `business failure carries backend message`() = runTest {
        val api = FakeCmsDanmakuApi(sendResponse = CmsDanmakuSendDto(code = 1003, msg = "发送太频繁"))

        val result = DefaultDanmuRepository(api)
            .send(DanmuTarget(124, 0, 0), content = "x", atTimeMs = 0, colorHex = "")

        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.Business)
        assertEquals("发送太频繁", (error as AppError.Business).message)
    }

    @Test
    fun `io failure becomes network error`() = runTest {
        val api = FakeCmsDanmakuApi(failure = IOException("timeout"))

        val result = DefaultDanmuRepository(api).list(DanmuTarget(124, 0, 0))

        assertTrue((result as AppResult.Failure).error is AppError.Network)
    }
}

private class FakeCmsDanmakuApi(
    private val listResponse: CmsDanmakuListDto = CmsDanmakuListDto(code = 1),
    private val sendResponse: CmsDanmakuSendDto = CmsDanmakuSendDto(code = 1, msg = "ok"),
    private val failure: Exception? = null,
) : CmsDanmakuApi {

    var listArgs: Triple<Int, Int, Int>? = null
        private set

    var sendFields: Map<String, String>? = null
        private set

    override suspend fun list(vodId: Int, sid: Int, nid: Int, limit: Int): CmsDanmakuListDto {
        failure?.let { throw it }
        listArgs = Triple(vodId, sid, nid)
        return listResponse
    }

    override suspend fun send(fields: Map<String, String>): CmsDanmakuSendDto {
        failure?.let { throw it }
        sendFields = fields
        return sendResponse
    }
}

/** 弹幕颜色常量放这里：颜色字面量只允许出现在 designsystem 令牌里，测试里用变量拼。 */
private const val HEX_COLOR = "FF7F00"
