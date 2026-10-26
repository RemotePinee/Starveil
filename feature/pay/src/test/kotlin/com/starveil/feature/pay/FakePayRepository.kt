package com.starveil.feature.pay

import com.starveil.core.common.AppResult
import com.starveil.core.data.PayRepository
import com.starveil.core.data.StartupRepository
import com.starveil.core.data.UserRepository
import com.starveil.core.common.ad.AdPlacements
import com.starveil.core.model.PayOrder
import com.starveil.core.model.PayPeriod
import com.starveil.core.model.PayPlan
import com.starveil.core.model.PayOrderStatus
import com.starveil.core.model.PayTips
import com.starveil.core.model.PaymentMethod
import com.starveil.core.model.CardRedeem
import com.starveil.core.model.CardType
import com.starveil.core.model.UserProfile
import com.starveil.core.model.SecondTab
import com.starveil.core.model.SkinId
import com.starveil.core.model.SkinResolution
import com.starveil.core.model.StartupConfig
import com.starveil.core.model.StartupConfig.Companion.DEFAULT_HOME_COLUMNS

/** 支付仓库假实现：每个入口的可控结果 + 最近一次调用参数，便于断言"传了什么"。 */
class FakePayRepository(
    var plansResult: AppResult<List<PayPlan>> = AppResult.Success(listOf(weekPlan(), monthPlan())),
    var payResult: AppResult<PayOrder> = AppResult.Success(
        PayOrder(orderCode = "PAY1", priceYuan = "30", points = 300, payUrl = "https://pay.test/h5"),
    ),
    var buyCardResult: AppResult<Unit> = AppResult.Success(Unit),
    var redeemResult: AppResult<CardRedeem> = AppResult.Success(
        CardRedeem(cardType = CardType.VIP, cardValue = 30, groupId = 3, membershipEnd = 1_799_999_999),
    ),
    var buyEpisodeResult: AppResult<Unit> = AppResult.Success(Unit),
    var tipsResult: AppResult<PayTips> = AppResult.Success(
        PayTips(payHint = "充值后自动到账", payUrl = "", goldHint = "", goldInfo = ""),
    ),
    var orderStatusResult: AppResult<PayOrderStatus> = AppResult.Success(paidOrderStatus()),
    var upgradeResult: AppResult<Unit> = AppResult.Success(Unit),
) : PayRepository {

    var lastPlanId: Int? = null
    var lastPaymentCode: String? = null
    var lastCardCode: String? = null
    var lastEpisode: Triple<Int, Int, Int>? = null
    var lastOrderCode: String? = null
    var lastUpgrade: Pair<Int, PayPeriod>? = null
    var orderStatusCalls: Int = 0

    override suspend fun plans(): AppResult<List<PayPlan>> = plansResult

    override suspend fun pay(planId: Int, paymentCode: String): AppResult<PayOrder> {
        lastPlanId = planId
        lastPaymentCode = paymentCode
        return payResult
    }

    override suspend fun buyCard(code: String): AppResult<Unit> {
        lastCardCode = code
        return buyCardResult
    }

    override suspend fun redeemCard(code: String): AppResult<CardRedeem> {
        lastCardCode = code
        return redeemResult
    }

    override suspend fun buyEpisode(
        vodId: Int,
        episodeNumber: Int,
        sourceIndex: Int,
        payType: Int,
    ): AppResult<Unit> {
        lastEpisode = Triple(vodId, episodeNumber, sourceIndex)
        return buyEpisodeResult
    }

    override suspend fun tips(): AppResult<PayTips> = tipsResult

    override suspend fun orderStatus(orderCode: String): AppResult<PayOrderStatus> {
        lastOrderCode = orderCode
        orderStatusCalls += 1
        return orderStatusResult
    }

    override suspend fun upgradeMembership(groupId: Int, period: PayPeriod): AppResult<Unit> {
        lastUpgrade = groupId to period
        return upgradeResult
    }
}

/** 启动配置假实现：只关心 payments（旧  从这里拿支付方式）。 */
class FakeStartupRepository(
    private val payments: List<PaymentMethod> = listOf(
        PaymentMethod(code = "alipay", name = "支付宝", status = 1),
        PaymentMethod(code = "wechat", name = "微信", status = 1),
    ),
) : StartupRepository {

    override suspend fun load(localThemeOverride: String?): AppResult<StartupConfig> =
        AppResult.Success(startupConfig(payments))
}

fun weekPlan() = PayPlan(id = 1, groupId = 3, period = PayPeriod.WEEK, priceYuan = 7, points = 70)

fun monthPlan() = PayPlan(id = 2, groupId = 3, period = PayPeriod.MONTH, priceYuan = 30, points = 300)

/** 已支付订单的默认夹具，字段与线上 `OrderBean` 同形（`order_status=1` 即已支付）。 */
fun paidOrderStatus() = PayOrderStatus(
    orderId = 12,
    userId = 64,
    orderStatus = 1,
    orderCode = "PAY1",
    priceYuan = "30.00",
    orderTime = 1574183766,
    points = 300,
    payType = "alipay",
    payTime = 1574183899,
    remarks = "",
)

private fun startupConfig(payments: List<PaymentMethod>) = StartupConfig(
    skin = SkinResolution(
        skin = SkinId.DEFAULT,
        fromLocalOverride = false,
        contractBroken = false,
        unknownCode = false,
    ),
    bannerStyleCode = null,
    secondTab = SecondTab.LIVE,
    homeCardColumns = DEFAULT_HOME_COLUMNS,
    appUiHomeSmallIcons = null,
    topBarUsesBanner = false,
    playRecommendEnabled = false,
    ads = AdPlacements(),
    payments = payments,
    searchHot = emptyList(),
)

/** 资料仓库替身：卡密兑换后只关心"被调了几次、返回什么"。 */
internal class FakeUserRepository(
    var result: AppResult<UserProfile> = AppResult.Success(demoProfile()),
) : UserRepository {

    var loadCalls: Int = 0
        private set

    override suspend fun loadProfile(): AppResult<UserProfile> {
        loadCalls += 1
        return result
    }
}

/** 资料样例，取值参考 `core/network/src/test/resources/fixtures/v1_user_detail.json`。 */
internal fun demoProfile(): UserProfile = UserProfile(
    userId = 72,
    userName = "demo",
    nickName = "示例昵称",
    portrait = "",
    groupId = 3,
    groupName = "黄金会员",
    level = "3",
    points = 0,
    userPoints = 10,
    gold = "129.00",
    status = 1,
    memberEndTime = 1_799_999_999,
    viewCount = "128",
    viewSeconds = 7_380,
    isVip = false,
    isAgents = "0",
)
