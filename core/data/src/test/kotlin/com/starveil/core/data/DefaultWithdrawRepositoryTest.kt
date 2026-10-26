package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.WithdrawChannel
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.GoldTipDto
import com.starveil.core.network.dto.WithdrawApplyResultDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 提现仓库：成功 / 业务失败 / 网络失败，外加文案解析降级。
 *
 * 两个接口的"失败"都不会抛异常：`goldTip` 是纯文案，`goldWithdrawApply` 成功也只代表
 * 申请入库。所以断言分两层——拿到的领域值，以及失败时透传的后端 `msg`。
 */
class DefaultWithdrawRepositoryTest {

    @Test
    fun `tips parses threshold and balance out of the chinese text`() = runTest {
        val api = FakeWithdrawApi(
            tip = {
                ApiEnvelopeDto(
                    code = 200,
                    msg = "success",
                    data = GoldTipDto(
                        msg = "最低提现20元",
                        info = "当前可提现金额5000元，提现比例10金币=1元",
                    ),
                )
            },
        )

        val tips = (DefaultWithdrawRepository(api).tips() as AppResult.Success).value

        assertEquals(20, tips.minAmountYuan)
        assertEquals(5000, tips.availableYuan)
        assertEquals("当前可提现金额5000元，提现比例10金币=1元", tips.ratioText)
        assertTrue(tips.isBelowMinimum(19))
    }

    @Test
    fun `apply posts amount channel account and real name`() = runTest {
        val api = FakeWithdrawApi(
            apply = { amount, channel, account, name ->
                ApiEnvelopeDto(
                    code = 200,
                    msg = "success",
                    data = WithdrawApplyResultDto(info = "提交成功[$amount/$channel/$account/$name]"),
                )
            },
        )

        val result = DefaultWithdrawRepository(api).apply(
            amount = 20,
            channel = WithdrawChannel.WECHAT,
            account = " wx_123 ",
            name = "张三",
        )

        // 仓库不做 trim，trim 由 ViewModel 负责（旧页在取文本时才 trim）
        assertEquals("20", api.lastAmount)
        assertEquals("2", api.lastChannel)
        assertEquals(" wx_123 ", api.lastAccount)
        assertEquals("张三", api.lastName)
        assertEquals("提交成功[20/2/ wx_123 /张三]", (result as AppResult.Success).value)
    }

    @Test
    fun `apply keeps backend business message on failure`() = runTest {
        val api = FakeWithdrawApi(apply = { _, _, _, _ -> ApiEnvelopeDto(code = 400, msg = "低于最低提现额") })

        val result = DefaultWithdrawRepository(api).apply(1, WithdrawChannel.ALIPAY, "a", "b")
        val error = (result as AppResult.Failure).error

        assertTrue(error is AppError.Business)
        assertEquals("低于最低提现额", (error as AppError.Business).message)
    }

    @Test
    fun `apply turns io exception into network error`() = runTest {
        val api = FakeWithdrawApi(apply = { _, _, _, _ -> throw IOException("offline") })

        val result = DefaultWithdrawRepository(api).apply(20, WithdrawChannel.ALIPAY, "a", "b")

        assertTrue((result as AppResult.Failure).error is AppError.Network)
    }

    @Test
    fun `apply forwards the qrcode path and drops a blank one`() = runTest {
        val api = FakeWithdrawApi(
            apply = { _, _, _, _ ->
                ApiEnvelopeDto(code = 200, msg = "success", data = WithdrawApplyResultDto(info = "ok"))
            },
        )
        val repository = DefaultWithdrawRepository(api)

        repository.apply(20, WithdrawChannel.ALIPAY, "a", "b", "/upload/withdraw/12.jpg")

        assertEquals("/upload/withdraw/12.jpg", api.lastQrcode)

        repository.apply(20, WithdrawChannel.ALIPAY, "a", "b")

        // 空串要变成 null，Retrofit 才肯把整个字段从表单里去掉
        assertTrue(api.lastQrcode == null)
    }
}

/** 只实现本类用到的两个接口，其余方法由 [StubStarveilApi] 兜底抛异常。 */
private class FakeWithdrawApi(
    private val tip: () -> ApiEnvelopeDto<GoldTipDto> = { unusedWithdrawApi() },
    private val apply: (String, String, String, String) -> ApiEnvelopeDto<WithdrawApplyResultDto> =
        { _, _, _, _ -> unusedWithdrawApi() },
) : StubStarveilApi() {

    var lastAmount: String? = null
    var lastChannel: String? = null
    var lastAccount: String? = null
    var lastName: String? = null
    var lastQrcode: String? = null

    override suspend fun goldTip(): ApiEnvelopeDto<GoldTipDto> = tip()

    override suspend fun withdrawApply(
        amount: String,
        channel: String,
        account: String,
        realName: String,
        qrcode: String?,
    ): ApiEnvelopeDto<WithdrawApplyResultDto> {
        lastAmount = amount
        lastChannel = channel
        lastAccount = account
        lastName = realName
        lastQrcode = qrcode
        return apply(amount, channel, account, realName)
    }
}

private fun unusedWithdrawApi(): Nothing = throw UnsupportedOperationException("not used in this test")
