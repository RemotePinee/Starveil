package com.starveil.feature.pay

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.CardRedeem
import com.starveil.core.model.CardType
import com.starveil.core.model.PayPeriod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 支付页状态机：加载、选套餐/支付方式、三条提交入口，以及回跳后的查单与兑换。
 *
 * 下单只断言"上抛了 H5 地址"；订单状态与会员兑换由 `onReturnedFromPayment` 断言，
 * 它对应 的 `checkOrder()`。
 */
class PayViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `loads plans and preselects the first plan and payment`() = runTest {
        val viewModel = viewModel()

        val state = viewModel.state.value

        assertFalse(state.isLoading)
        assertEquals(listOf(1, 2), state.plans.map { it.id })
        assertEquals(1, state.selectedPlanId)
        assertEquals("alipay", state.selectedPaymentCode)
        assertEquals("充值后自动到账", state.tips?.payHint)
    }

    @Test
    fun `selecting another payment updates state`() = runTest {
        val viewModel = viewModel()

        viewModel.onPaymentSelected("wechat")

        assertEquals("wechat", viewModel.state.value.selectedPaymentCode)
    }

    @Test
    fun `submit sends order and emits open payment event`() = runTest {
        val repository = FakePayRepository()
        val viewModel = viewModel(repository)
        viewModel.onPaymentSelected("wechat")

        viewModel.submit()

        val event = viewModel.events.first()
        assertTrue(event is PayEvent.OpenPayment)
        assertEquals("https://pay.test/h5", (event as PayEvent.OpenPayment).url)
        assertEquals(1, repository.lastPlanId)
        assertEquals("wechat", repository.lastPaymentCode)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    @Test
    fun `redeem card success clears input and notifies membership days`() = runTest {
        val repository = FakePayRepository()
        val viewModel = viewModel(repository)
        viewModel.onCardCodeChange("  card-123  ")

        viewModel.redeemCard()

        val event = viewModel.events.first()
        // 提示按卡类型给天数，不再写死"会员已到账"（后端 v1.user/buy 的 data.card_type/card_value）
        assertEquals(PayEvent.Message("兑换成功，会员已延长 30 天"), event)
        // 提交前 trim + 转大写：后台 card_no 是大写十六进制
        assertEquals("CARD-123", repository.lastCardCode)
        assertEquals("", viewModel.state.value.cardCode)
    }

    @Test
    fun `redeem gold card reports coins instead of membership`() = runTest {
        val repository = FakePayRepository(
            redeemResult = AppResult.Success(CardRedeem(cardType = CardType.GOLD, cardValue = 100)),
        )
        val viewModel = viewModel(repository)
        viewModel.onCardCodeChange("gold-1")

        viewModel.redeemCard()

        assertEquals(PayEvent.Message("兑换成功，获得 100 金币"), viewModel.events.first())
        assertEquals("GOLD-1", repository.lastCardCode)
    }

    @Test
    fun `redeem card refreshes the user profile once`() = runTest {
        val users = FakeUserRepository()
        val viewModel = viewModel(userRepository = users)
        // 进页时已为 Hero 读过一次会员状态；这里只关心"兑换后是否再读一次"
        val callsOnOpen = users.loadCalls
        viewModel.onCardCodeChange("card-123")

        viewModel.redeemCard()

        // 兑换后必须重读一次 v1.user/detail，会员到期/金币才是新值
        assertEquals(callsOnOpen + 1, users.loadCalls)
        assertEquals(1_799_999_999L, viewModel.state.value.profile?.memberEndTime)
    }

    @Test
    fun `hero status reports not subscribed when member end is zero`() = runTest {
        val users = FakeUserRepository(result = AppResult.Success(demoProfile().copy(memberEndTime = 0)))

        val viewModel = viewModel(userRepository = users)

        // memberEndTime = 0 是"未开通"，不能当成 1970 年渲染
        assertEquals("尚未开通会员，开通后可畅看会员专区", viewModel.state.value.memberStatusLabel)
        assertFalse(viewModel.state.value.isMember)
    }

    @Test
    fun `hero status ignores member end when the server says not vip`() = runTest {
        // 回归：后台新建的游客账号也有 user_end_time，按时间戳判会把非会员写成"有效期至 X"
        val users = FakeUserRepository(result = AppResult.Success(demoProfile()))

        val viewModel = viewModel(userRepository = users)

        assertEquals("尚未开通会员，开通后可畅看会员专区", viewModel.state.value.memberStatusLabel)
        assertFalse(viewModel.state.value.isMember)
    }

    @Test
    fun `hero status shows the expiry for a vip`() = runTest {
        val users = FakeUserRepository(result = AppResult.Success(demoProfile().copy(isVip = true)))

        val viewModel = viewModel(userRepository = users)

        assertTrue(viewModel.state.value.memberStatusLabel.startsWith("会员有效期至 "))
        assertTrue(viewModel.state.value.isMember)
    }

    @Test
    fun `sync session drops the profile once the session is gone`() = runTest {
        // 会员页是常驻页面，退出登录不会重建 ViewModel：必须自己把旧账号的资料清掉
        val users = FakeUserRepository(result = AppResult.Success(demoProfile()))
        val auth = FakePayAuthRepository()
        val viewModel = viewModel(userRepository = users, authRepository = auth)
        assertTrue(viewModel.state.value.isLoggedIn == true)

        auth.restored = null
        viewModel.syncSession()

        assertNull(viewModel.state.value.profile)
        assertEquals(false, viewModel.state.value.isLoggedIn)
        assertEquals("登录后查看会员状态", viewModel.state.value.memberStatusLabel)
    }

    @Test
    fun `hero status falls back to a login hint without a profile`() = runTest {
        val users = FakeUserRepository(result = AppResult.Failure(AppError.Business(401, "未登录")))

        val viewModel = viewModel(
            userRepository = users,
            authRepository = FakePayAuthRepository(restored = null),
        )

        // 资料读不到时不编造日期，Hero 给一句可执行提示
        assertNull(viewModel.state.value.profile)
        assertEquals("登录后查看会员状态", viewModel.state.value.memberStatusLabel)
    }

    @Test
    fun `too long card code is rejected without a request`() = runTest {
        val repository = FakePayRepository()
        val viewModel = viewModel(repository)
        viewModel.onCardCodeChange("A".repeat(33))

        viewModel.redeemCard()

        assertEquals(PayEvent.Message("卡密格式不正确，请核对后重试"), viewModel.events.first())
        assertNull(repository.lastCardCode)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    @Test
    fun `redeem failure keeps backend message`() = runTest {
        val repository = FakePayRepository(
            redeemResult = AppResult.Failure(AppError.Business(409, "卡密已被使用")),
        )
        val viewModel = viewModel(repository)
        viewModel.onCardCodeChange("CARD-409")

        viewModel.redeemCard()

        assertEquals(PayEvent.Message("卡密已被使用"), viewModel.events.first())
        // 失败不清输入框，用户还能核对卡号
        assertEquals("CARD-409", viewModel.state.value.cardCode)
    }

    @Test
    fun `buy episode failure notifies backend message`() = runTest {
        val repository = FakePayRepository(
            buyEpisodeResult = AppResult.Failure(AppError.Business(403, "余额不足")),
        )
        val viewModel = viewModel(
            repository = repository,
            args = PayArgs(mode = PayMode.EPISODE, vodId = 101, episodeNumber = 3, sourceIndex = 1),
        )

        viewModel.buyEpisode()

        val event = viewModel.events.first()
        assertEquals(PayEvent.Message("余额不足"), event)
        assertEquals(Triple(101, 3, 1), repository.lastEpisode)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    @Test
    fun `returning from payment activates membership for a paid order`() = runTest {
        val repository = FakePayRepository()
        val viewModel = viewModel(repository)

        viewModel.submit()
        viewModel.onReturnedFromPayment()

        val state = viewModel.state.value
        assertEquals("PAY1", repository.lastOrderCode)
        // 下单选中的是周卡（id=1, groupId=3），兑换要带上它的组 id 与档位
        assertEquals(3 to PayPeriod.WEEK, repository.lastUpgrade)
        assertTrue(state.membershipActivated)
        assertEquals("支付成功，会员已开通", state.lastOrderMessage)
        assertNull(state.pendingOrderCode)
        assertFalse(state.isCheckingOrder)
    }

    @Test
    fun `returning from payment without a pending order queries nothing`() = runTest {
        val repository = FakePayRepository()
        val viewModel = viewModel(repository)

        viewModel.onReturnedFromPayment()

        assertNull(repository.lastOrderCode)
        assertEquals(0, repository.orderStatusCalls)
        assertNull(viewModel.state.value.orderStatus)
        assertFalse(viewModel.state.value.isCheckingOrder)
    }

    @Test
    fun `order check failure keeps the message and never re-queries`() = runTest {
        val repository = FakePayRepository(
            orderStatusResult = AppResult.Failure(AppError.Business(500, "订单查询失败")),
        )
        val viewModel = viewModel(repository)

        viewModel.submit()
        viewModel.onReturnedFromPayment()
        viewModel.onReturnedFromPayment()

        val state = viewModel.state.value
        assertEquals("订单查询失败", state.lastOrderMessage)
        assertFalse(state.membershipActivated)
        // 失败后清掉待查订单号，第二次回跳不再打接口（ onError 也是直接清 orderCode）
        assertEquals(1, repository.orderStatusCalls)
        assertNull(state.pendingOrderCode)
    }

    private fun viewModel(
        repository: FakePayRepository = FakePayRepository(),
        userRepository: FakeUserRepository = FakeUserRepository(),
        authRepository: FakePayAuthRepository = FakePayAuthRepository(),
        args: PayArgs = PayArgs(),
    ) = PayViewModel(
        payRepository = repository,
        startupRepository = FakeStartupRepository(),
        userRepository = userRepository,
        authRepository = authRepository,
        args = args,
    )
}
