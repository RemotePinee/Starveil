package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.PayPeriod
import com.starveil.core.model.PayPlan
import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.GroupUpgradeDto
import com.starveil.core.network.dto.MemberGroupCatalogDto
import com.starveil.core.network.dto.MemberGroupDto
import com.starveil.core.network.dto.OrderDto
import com.starveil.core.network.dto.OrderStatusDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 支付仓库：成功 / 业务失败 / 网络失败 / 空数据四条主路径，外加下单拼 H5 地址。
 *
 * 价格折算是的整数除法，所以夹具里日卡 9 积分
 * 折不出元，必须被过滤掉而不是变成 ¥0 套餐。
 */
class DefaultPayRepositoryTest {

    @Test
    fun `plans maps paid group tiers and drops zero price tiers`() = runTest {
        val repository = DefaultPayRepository(FakePayApi(groups = { success(groupCatalog()) }), TEST_HOST)

        val plans = (repository.plans() as AppResult.Success).value

        assertEquals(listOf(2, 3, 4), plans.map { it.id })
        assertEquals(listOf(7, 30, 360), plans.map { it.priceYuan })
        assertTrue(plans.all { it.groupId == 3 })
    }

    @Test
    fun `plans keeps backend business message on failure`() = runTest {
        val repository = DefaultPayRepository(
            FakePayApi(groups = { ApiEnvelopeDto(code = 403, msg = "请先登录") }),
            TEST_HOST,
        )

        val error = (repository.plans() as AppResult.Failure).error

        assertTrue(error is AppError.Business)
        assertEquals("请先登录", (error as AppError.Business).message)
    }

    @Test
    fun `plans turns io exception into network error`() = runTest {
        val repository = DefaultPayRepository(
            FakePayApi(groups = { throw IOException("offline") }),
            TEST_HOST,
        )

        assertTrue((repository.plans() as AppResult.Failure).error is AppError.Network)
    }

    @Test
    fun `plans returns empty list when catalog has no paid group`() = runTest {
        val empty = ApiEnvelopeDto(code = 200, msg = "success", data = MemberGroupCatalogDto())
        val repository = DefaultPayRepository(FakePayApi(groups = { empty }), TEST_HOST)

        val plans = (repository.plans() as AppResult.Success).value

        assertEquals(emptyList<PayPlan>(), plans)
    }

    @Test
    fun `pay orders the plan price and builds the h5 payment url`() = runTest {
        val order = OrderDto(orderCode = "PAY1", orderPrice = "30", orderPoints = 300)
        val api = FakePayApi(
            groups = { success(groupCatalog()) },
            order = { ApiEnvelopeDto(code = 200, msg = "下单成功", data = order) },
        )

        val result = DefaultPayRepository(api, TEST_HOST).pay(planId = 3, paymentCode = "alipay")

        val payOrder = (result as AppResult.Success).value
        assertEquals("30", api.lastPrice)
        // 支付通道必须随建单上报：只拿去拼支付页地址等于没传给服务端
        assertEquals("alipay", api.lastPayment)
        assertEquals("PAY1", payOrder.orderCode)
        assertEquals(
            "$TEST_HOST/icciu_api.php/v1.user/pay?payment=alipay&order_code=PAY1",
            payOrder.payUrl,
        )
    }

    @Test
    fun `order status maps the paid order returned by the backend`() = runTest {
        val api = FakePayApi(
            status = {
                ApiEnvelopeDto(
                    code = 200,
                    msg = "success",
                    data = OrderStatusDto(
                        orderId = 12,
                        userId = 64,
                        orderStatus = 1,
                        orderCode = "PAY1",
                        orderPrice = "30.00",
                        orderTime = 1574183766,
                        orderPoints = 300,
                        orderPayType = "alipay",
                        orderPayTime = 1574183899,
                    ),
                )
            },
        )

        val status = (DefaultPayRepository(api, TEST_HOST).orderStatus("PAY1") as AppResult.Success).value

        assertEquals("PAY1", api.lastOrderCode)
        assertTrue(status.isPaid)
        assertEquals("30.00", status.priceYuan)
        assertEquals(300, status.points)
        assertEquals(1574183899L, status.payTime)
    }

    @Test
    fun `order status keeps business message when the envelope fails`() = runTest {
        val api = FakePayApi(status = { ApiEnvelopeDto(code = 404, msg = "订单不存在") })

        val error = (DefaultPayRepository(api, TEST_HOST).orderStatus("PAY1") as AppResult.Failure).error

        assertTrue(error is AppError.Business)
        assertEquals("订单不存在", (error as AppError.Business).message)
    }

    @Test
    fun `upgrade membership posts the period code and group id`() = runTest {
        val api = FakePayApi(upgrade = { ApiEnvelopeDto(code = 200, msg = "success") })

        val result = DefaultPayRepository(api, TEST_HOST).upgradeMembership(3, PayPeriod.MONTH)

        assertTrue(result is AppResult.Success)
        // 旧接口字段 long = 时长档位串，group_id = 会员组
        assertEquals("month", api.lastPeriodCode)
        assertEquals("3", api.lastGroupId)
    }

    @Test
    fun `upgrade membership turns io exception into network error`() = runTest {
        val api = FakePayApi(upgrade = { throw IOException("offline") })

        val result = DefaultPayRepository(api, TEST_HOST).upgradeMembership(3, PayPeriod.WEEK)

        assertTrue((result as AppResult.Failure).error is AppError.Network)
    }
}

/** 只实现本类用到的两个接口，其余方法由 [StubStarveilApi] 兜底抛异常。 */
private class FakePayApi(
    private val groups: () -> ApiEnvelopeDto<MemberGroupCatalogDto> = { unusedPayApi() },
    private val order: () -> ApiEnvelopeDto<OrderDto> = { unusedPayApi() },
    private val status: () -> ApiEnvelopeDto<OrderStatusDto> = { unusedPayApi() },
    private val upgrade: () -> ApiEnvelopeDto<GroupUpgradeDto> = { unusedPayApi() },
) : StubStarveilApi() {

    var lastPrice: String? = null
    var lastPayment: String? = null
    var lastOrderCode: String? = null
    var lastPeriodCode: String? = null
    var lastGroupId: String? = null

    override suspend fun memberGroups(): ApiEnvelopeDto<MemberGroupCatalogDto> = groups()

    override suspend fun createOrder(price: String, payment: String): ApiEnvelopeDto<OrderDto> {
        lastPrice = price
        lastPayment = payment
        return order()
    }

    override suspend fun orderStatus(orderCode: String): ApiEnvelopeDto<OrderStatusDto> {
        lastOrderCode = orderCode
        return status()
    }

    override suspend fun upgradeGroup(
        period: String,
        groupId: String,
        orderCode: String,
    ): ApiEnvelopeDto<GroupUpgradeDto> {
        lastPeriodCode = period
        lastGroupId = groupId
        lastOrderCode = orderCode
        return upgrade()
    }
}

private fun success(catalog: MemberGroupCatalogDto) =
    ApiEnvelopeDto(code = 200, msg = "success", data = catalog)

/** 日卡 9 积分（折不出元）+ 周/月/年三档，与线上价目表同形。 */
private fun groupCatalog() = MemberGroupCatalogDto(
    list = mapOf(
        "3" to MemberGroupDto(
            groupId = 3,
            groupName = "VIP会员",
            groupStatus = 1,
            pointsDay = 9,
            pointsWeek = 70,
            pointsMonth = 300,
            pointsYear = 3600,
        ),
    ),
)

/** 站点根地址只用于拼 H5 地址，测试里用保留域名，不指向真实站点。 */
private const val TEST_HOST = "https://starveil.test"

private fun unusedPayApi(): Nothing = throw UnsupportedOperationException("not used in this test")
