package com.starveil.feature.user

import app.cash.turbine.test
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.WithdrawChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 提现页状态机：金额校验、门槛拦截、提交成功与失败。
 *
 * 四条用例对应的都是"本地闸门"与"提交结果"，不测 UI：
 * 旧页的判空散落在 的点击回调里，这里把它变成
 * [WithdrawUiState] 的派生属性，所以可以直接断言状态而不用起界面。
 */
class WithdrawViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `non integer amount is rejected before calling the repository`() = runTest {
        val repository = FakeWithdrawRepository()
        val viewModel = viewModel(repository)
        viewModel.onAmountChange("12.5")
        viewModel.onAccountChange("alipay@test")
        viewModel.onNameChange("张三")

        assertEquals("请输入整数金额", viewModel.state.value.amountError)
        assertFalse(viewModel.state.value.canSubmit)

        viewModel.submit()

        assertEquals(WithdrawEvent.Message("请输入整数金额"), viewModel.events.first())
        assertEquals(0, repository.applyCalls)
    }

    @Test
    fun `amount below the minimum is blocked locally`() = runTest {
        val repository = FakeWithdrawRepository()
        val viewModel = viewModel(repository)
        viewModel.onAmountChange("10")
        viewModel.onAccountChange("alipay@test")
        viewModel.onNameChange("张三")

        val state = viewModel.state.value
        assertEquals(20, state.minAmountYuan)
        assertTrue(state.isBelowMinimum)
        assertEquals("最低提现 20 元", state.amountError)
        assertFalse(state.canSubmit)

        viewModel.submit()

        assertEquals(WithdrawEvent.Message("最低提现 20 元"), viewModel.events.first())
        assertEquals(0, repository.applyCalls)
    }

    @Test
    fun `submit success marks the request as submitted`() = runTest {
        val repository = FakeWithdrawRepository()
        val viewModel = viewModel(repository)
        viewModel.onAmountChange("25")
        viewModel.onAccountChange(" wx_123 ")
        viewModel.onNameChange(" 张三 ")
        viewModel.onChannelSelected(WithdrawChannel.WECHAT)

        viewModel.submit()

        val state = viewModel.state.value
        assertTrue(state.submitted)
        assertFalse(state.isSubmitting)
        // 空格外由 ViewModel 去掉，旧页也是取文本时才 trim
        assertEquals(AppliedWithdraw(25, WithdrawChannel.WECHAT, "wx_123", "张三", ""), repository.lastApply)
    }

    @Test
    fun `submit failure emits backend message and keeps the form`() = runTest {
        val repository = FakeWithdrawRepository(
            applyResult = AppResult.Failure(AppError.Business(400, "余额不足")),
        )
        val viewModel = viewModel(repository)
        viewModel.onAmountChange("25")
        viewModel.onAccountChange("alipay@test")
        viewModel.onNameChange("张三")

        viewModel.submit()

        assertEquals(WithdrawEvent.Message("余额不足"), viewModel.events.first())
        val state = viewModel.state.value
        assertFalse(state.submitted)
        assertFalse(state.isSubmitting)
        // 失败保留输入，用户不必重填
        assertEquals("25", state.amount)
        assertNull(state.loadError)
    }

    @Test
    fun `amount above the available balance is rejected locally`() = runTest {
        val repository = FakeWithdrawRepository()
        val viewModel = viewModel(repository)
        viewModel.onAmountChange("6000")
        viewModel.onAccountChange("alipay@test")
        viewModel.onNameChange("张三")

        assertEquals("超出可提现金额（可提现 5000 元）", viewModel.state.value.amountError)
        assertFalse(viewModel.state.value.canSubmit)

        viewModel.submit()

        assertEquals(WithdrawEvent.Message("超出可提现金额（可提现 5000 元）"), viewModel.events.first())
        assertEquals(0, repository.applyCalls)
    }

    @Test
    fun `unknown failure locks the form until the user confirms`() = runTest {
        val repository = FakeWithdrawRepository(
            applyResult = AppResult.Failure(AppError.Network(null)),
        )
        val viewModel = viewModel(repository)
        viewModel.onAmountChange("25")
        viewModel.onAccountChange("alipay@test")
        viewModel.onNameChange("张三")

        viewModel.submit()

        val pending = viewModel.state.value
        assertNotNull(pending.pendingConfirm)
        assertFalse(pending.isSubmitting)
        assertFalse(pending.canSubmit)
        assertTrue(pending.requestId.isNotBlank())

        // 结果未知期间再点提交不会产生第二笔申请
        viewModel.submit()
        assertEquals(1, repository.applyCalls)

        // 用户主动确认后按原请求、原幂等键重试
        viewModel.onPendingConfirmed()
        assertEquals(2, repository.applyCalls)
        assertEquals(pending.requestId, viewModel.state.value.requestId)
    }

    @Test
    fun `qrcode upload exposes progress then stores the returned path`() = runTest {
        val gate = CompletableDeferred<AppResult<String>>()
        val repository = FakeWithdrawRepository()
        repository.uploadGate = gate
        val viewModel = viewModel(repository)

        viewModel.state.test {
            assertEquals("", awaitItem().qrcodePath)

            viewModel.onQrcodePicked(qrcodePick())
            assertTrue(awaitItem().uploadingQrcode)

            gate.complete(AppResult.Success(FAKE_QRCODE_PATH))
            val uploaded = awaitItem()
            assertFalse(uploaded.uploadingQrcode)
            assertEquals(FAKE_QRCODE_PATH, uploaded.qrcodePath)
            // 缩略图用本地 uri：服务端路径要拼域名才能显示，页面拿不到 host
            assertEquals(QRCODE_URI, uploaded.qrcodePreview)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `qrcode upload failure emits the backend message and keeps the path empty`() = runTest {
        val repository = FakeWithdrawRepository(
            uploadResult = AppResult.Failure(AppError.Business(413, "图片过大")),
        )
        val viewModel = viewModel(repository)

        viewModel.onQrcodePicked(qrcodePick())

        assertEquals(WithdrawEvent.Message("图片过大"), viewModel.events.first())
        val state = viewModel.state.value
        assertFalse(state.uploadingQrcode)
        assertFalse(state.hasQrcode)
        assertEquals(1, repository.uploadCalls)
        assertEquals("qrcode.jpg", repository.lastUpload?.fileName)
    }

    @Test
    fun `submit sends the uploaded qrcode path`() = runTest {
        val repository = FakeWithdrawRepository()
        val viewModel = viewModel(repository)
        viewModel.onQrcodePicked(qrcodePick())
        viewModel.onAmountChange("25")
        viewModel.onAccountChange("alipay@test")
        viewModel.onNameChange("张三")

        viewModel.submit()

        assertTrue(viewModel.state.value.submitted)
        assertEquals(FAKE_QRCODE_PATH, repository.lastApply?.qrcode)
    }

    @Test
    fun `removing the qrcode submits without the field`() = runTest {
        val repository = FakeWithdrawRepository()
        val viewModel = viewModel(repository)
        viewModel.onQrcodePicked(qrcodePick())
        viewModel.onQrcodeRemoved()
        viewModel.onAmountChange("25")
        viewModel.onAccountChange("alipay@test")
        viewModel.onNameChange("张三")

        viewModel.submit()

        assertEquals("", repository.lastApply?.qrcode)
    }

    @Test
    fun `submit is blocked while the qrcode is uploading`() = runTest {
        val repository = FakeWithdrawRepository()
        repository.uploadGate = CompletableDeferred()
        val viewModel = viewModel(repository)
        viewModel.onQrcodePicked(qrcodePick())
        viewModel.onAmountChange("25")
        viewModel.onAccountChange("alipay@test")
        viewModel.onNameChange("张三")

        assertFalse(viewModel.state.value.canSubmit)

        viewModel.submit()

        assertEquals(0, repository.applyCalls)
    }

    private fun qrcodePick() = QrcodePick(
        uri = QRCODE_URI,
        bytes = byteArrayOf(1, 2, 3),
        fileName = "qrcode.jpg",
        mime = "image/jpeg",
    )

    private fun viewModel(repository: FakeWithdrawRepository) = WithdrawViewModel(repository)
}

private const val QRCODE_URI = "content://media/external/images/1"
