package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.common.getOrNull
import com.starveil.core.common.map
import com.starveil.core.common.onSuccess
import com.starveil.core.model.CardRedeem
import com.starveil.core.model.PayOrder
import com.starveil.core.model.PayOrderStatus
import com.starveil.core.model.PayPeriod
import com.starveil.core.model.PayPlan
import com.starveil.core.model.PayTips
import com.starveil.core.network.ApiPaths
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.dto.MemberGroupCatalogDto
import com.starveil.core.network.mapper.toDomain
import com.starveil.core.network.mapper.toPlans
import kotlinx.serialization.serializer

/**
 * 支付仓库：会员价目表、充值下单、卡密、单集购买、充值提示。
 *
 * 把这些调用散在支付、会员、播放三个页面里，
 * 每处自己判 `code`、自己拼 URL；这里统一收敛成 `AppResult`，页面只认成功/失败。
 */
interface PayRepository {

    /**
     * 会员价目表，来源是 `GET v1.user/groups`（
     * 路径常量 `SCORE_LIST`）。
     *
     * 后端不给"套餐列表"接口：它的形态是「组 -> 四个时长价」，固定读
     * 键 `"3"` 的 VIP 会员组。价格是积分值，除以 10 得元。
     * 返回空列表表示该组没配价或没下发，页面走空态。
     */
    suspend fun plans(): AppResult<List<PayPlan>>

    /**
     * 下单并拼出 H5 支付地址。
     *
     * 两段旧逻辑合成一次调用：
     * 1. `POST v1.user/order`（表单 `price`）拿到 `order_code`；
     * 2. 拼 `{host}/icciu_api.php/v1.user/pay?payment={paymentCode}&order_code={orderCode}`
     * 交给 WebView 打开。
     *
     * [planId] 是 [PayPlan.id]，不是后端字段：下单接口只认金额，所以这里会先拉一次价目表
     * 把套餐编号换成价格。多一次 GET，是为了让 UI 不必把金额当参数传进仓库。
     */
    suspend fun pay(planId: Int, paymentCode: String): AppResult<PayOrder>

    /**
     * 卡密兑换（`POST v1.user/buy`）。
     *
     * 返回核销结果 [CardRedeem]：老后端不返回 `data` 里的类型字段时各字段取默认值，
     * 页面仍按"兑换成功"提示；老入口 [buyCard] 保留给只关心成功与否的调用方。
     *
     * 接口给默认实现，是为了让不关心核销结果的假仓库（各 feature 的测试）不必实现它。
     */
    suspend fun redeemCard(code: String): AppResult<CardRedeem> = AppResult.Success(CardRedeem())

    /** 卡密兑换（旧签名，只看成功/失败）。需要卡类型与面值时用 [redeemCard]。 */
    suspend fun buyCard(code: String): AppResult<Unit>

    /**
     * 单集购买（`POST v1.user/buypopedom`）。
     *
     * @param vodId 影片 id
     * @param episodeNumber 单集 nid，1 起
     * @param sourceIndex 播放源数组下标，0 起；请求里按后端 `sid`（1 起）传
     * @param payType 恒为 1
     */
    suspend fun buyEpisode(
        vodId: Int,
        episodeNumber: Int,
        sourceIndex: Int,
        payType: Int = 1,
    ): AppResult<Unit>

    /**
     * 充值提示，合并 `v1.user/payTip` 与 `v1.user/goldTip`。
     *
     * 合并原因：两者都只是文案，分别调在支付页与
     * 金币提现页，拆成两个方法只会让调用方写两遍同样的
     * 错误处理。payTip 失败算整体失败（页面要显示提示与在线充值入口）；
     * goldTip 失败只让金币文案为空，不影响支付。
     */
    suspend fun tips(): AppResult<PayTips>

    /**
     * 查订单状态（`GET v1.user/order?order_code=`，旧 `checkOrder`）。
     *
     * 支付页从 H5 回跳后调用一次。返回 [PayOrderStatus]，未支付看 `isPaid`，
     * 不要拿 `order_status` 的原始数字到页面里比较——"0 是未支付"这个约定只住在模型层。
     *
     * 调用方不要轮询：回跳后只查一次，查完就清掉 `orderCode`。
     */
    suspend fun orderStatus(orderCode: String): AppResult<PayOrderStatus>

    /**
     * 会员兑换（`POST v1.user/group`，旧 `upgradeGroup`）。
     *
     * 真实签名比任务描述多一个 [period]：旧接口两个表单字段
     * `long`（时长档位串 day/week/month/year）与 `group_id`，
     * 只传组 id 兑换不了，后端不知道买多久。
     *
     * `group_id` 口径需要后端确认：
     * 传的是用户当前所在组，
     * 新版传 [PayPlan.groupId]（价目表里被购买的那个组，线上是 3）。
     * 老用户两者一致；新用户当前组不是会员组时，哪个才是后端想要的只有接口文档能回答，
     * 按"买哪个组就传哪个组"实现，要改只有本实现一行。
     *
     * 必须先确认订单已支付再调，否则等于白扣积分。
     */
    suspend fun upgradeMembership(groupId: Int, period: PayPeriod): AppResult<Unit>

    /**
     * 带订单号的兑换（生产路径）。
     *
     * 服务端按 `order_code` 校验「已支付 + 属于本人 + 未开通过」，重复调用幂等返回成功；
     * 不带订单号时服务端只能退回到「5 分钟内已支付订单」的兼容窗口。
     *
     * 默认实现挂 [upgradeMembership] 的二参版本，其它模块手写的 fake 不必跟着改签名。
     */
    suspend fun upgradeMembership(
        groupId: Int,
        period: PayPeriod,
        orderCode: String,
    ): AppResult<Unit> = upgradeMembership(groupId, period)

    /**
     * 上次成功响应的价目表快照（冷启动首帧用）。
     *
     * 给默认实现：其它模块手写的 fake 不必为快照额外实现一遍。
     */
    fun cachedPlans(): List<PayPlan>? = null
}

/**
 * 仓库实现。异常统一由 [apiCall] 收敛，这里只做"响应壳 -> 领域模型"的判定。
 *
 * @param h5Host 后端站点根地址（`https://host`，不带路径），由 `:app` 的 AppContainer
 * 从配置注入；只用来拼 H5 支付页地址，禁止在这里写死域名。
 * @param snapshot 价目表首帧快照；为 null 时不落盘（测试与其它入口构造本类时不必提供）。
 */
class DefaultPayRepository(
    private val api: StarveilApi,
    private val h5Host: String,
    private val snapshot: PageSnapshotStore? = null,
) : PayRepository {

    override fun cachedPlans(): List<PayPlan>? =
        snapshot?.read(PageSnapshotStore.KEY_PAY_PLANS, serializer<MemberGroupCatalogDto>())
            ?.list?.get(PAID_GROUP_KEY)?.toPlans()

    override suspend fun plans(): AppResult<List<PayPlan>> =
        apiCall { api.memberGroups() }
            .onSuccess { envelope -> saveCatalog(envelope.data) }
            .flatMap { envelope ->
                envelope.data
                    ?.let { catalog -> AppResult.Success(catalog.list[PAID_GROUP_KEY]?.toPlans().orEmpty()) }
                    ?: businessFailure(envelope.code, envelope.msg)
            }

    /**
     * 存整份目录而不是解析后的列表：后端加字段不会让快照作废，读取时再取 VIP 组。
     * 只写在成功分支——上一次的好数据比这次的错误更有用。
     */
    private fun saveCatalog(catalog: MemberGroupCatalogDto?) {
        if (catalog == null) return
        snapshot?.write(PageSnapshotStore.KEY_PAY_PLANS, serializer<MemberGroupCatalogDto>(), catalog)
    }

    override suspend fun pay(planId: Int, paymentCode: String): AppResult<PayOrder> =
        plans()
            .flatMap { plans -> plans.planOf(planId) }
            .flatMap { plan -> createOrder(plan.priceYuan.toString(), paymentCode) }

    override suspend fun buyCard(code: String): AppResult<Unit> =
        apiCall { api.buyCard(code) }.requireSuccess()

    override suspend fun redeemCard(code: String): AppResult<CardRedeem> =
        apiCall { api.buyCard(code) }
            .requireData()
            .map { dto -> dto.toDomain() }

    override suspend fun buyEpisode(
        vodId: Int,
        episodeNumber: Int,
        sourceIndex: Int,
        payType: Int,
    ): AppResult<Unit> = apiCall {
        api.buyEpisode(buyEpisodeFields(vodId, episodeNumber, sourceIndex, payType))
    }.requireSuccess()

    override suspend fun orderStatus(orderCode: String): AppResult<PayOrderStatus> =
        apiCall { api.orderStatus(orderCode) }
            .requireData()
            .map { status -> status.toDomain() }

    override suspend fun upgradeMembership(
        groupId: Int,
        period: PayPeriod,
        orderCode: String,
    ): AppResult<Unit> =
        apiCall { api.upgradeGroup(period.code, groupId.toString(), orderCode) }.requireSuccess()

    override suspend fun upgradeMembership(groupId: Int, period: PayPeriod): AppResult<Unit> =
        upgradeMembership(groupId, period, orderCode = "")

    override suspend fun tips(): AppResult<PayTips> =
        apiCall { api.payTip() }.flatMap { envelope ->
            if (envelope.code != SUCCESS_CODE) return@flatMap businessFailure(envelope.code, envelope.msg)
            // goldTip 与支付无关，失败时只丢文案，不把整页打成错误态
            val gold = apiCall { api.goldTip() }.getOrNull()?.data
            AppResult.Success(
                PayTips(
                    payHint = envelope.msg.orEmpty(),
                    payUrl = envelope.data?.url.orEmpty(),
                    goldHint = gold?.msg.orEmpty(),
                    goldInfo = gold?.info.orEmpty(),
                ),
            )
        }

    private suspend fun createOrder(price: String, paymentCode: String): AppResult<PayOrder> =
        apiCall { api.createOrder(price) }
            .requireData()
            .map { order -> order.toDomain(payPageUrl(order.orderCode, paymentCode)) }

    /** 自己拼 H5 支付页地址，host 由 `:app` 注入。 */
    private fun payPageUrl(orderCode: String, paymentCode: String): String =
        h5Host.trimEnd('/') + ApiPaths.absolute(ApiPaths.PAY) +
            "?payment=" + paymentCode + "&order_code=" + orderCode
}

/**
 * 套餐编号 -> 价格。本地失败没有后端业务码，`code` 统一填 0
 * （[com.starveil.core.common.AppError.Business] 的 code 只用于展示）。
 */
private fun List<PayPlan>.planOf(planId: Int): AppResult<PayPlan> =
    firstOrNull { it.id == planId }
        ?.let { AppResult.Success(it) }
        ?: businessFailure(0, "套餐已下架，请刷新后重试")

/**
 * 单集购买的表单。字段名与旧调用逐字一致：
 * `type` 恒 "4"、`mid` 恒 "1"、`pay_type` 默认 "1"。
 *
 * `sid` 取 `playFrom.sid`；新模型 `PlaySource` 只保留下标、没保留 sid
 * （`Vod.kt` 的注释说明二者可能不一致）。契约夹具里首个源 sid=1 对应下标 0，
 * 故这里传 `sourceIndex + 1`。后端若下发与顺序不一致的 sid，需要把 sid 补进 PlaySource。
 */
private fun buyEpisodeFields(
    vodId: Int,
    episodeNumber: Int,
    sourceIndex: Int,
    payType: Int,
): Map<String, String> = mapOf(
    "type" to BUY_EPISODE_TYPE,
    "id" to vodId.toString(),
    "sid" to (sourceIndex + SOURCE_SID_OFFSET).toString(),
    "nid" to episodeNumber.toString(),
    "mid" to BUY_EPISODE_MODULE_ID,
    "pay_type" to payType.toString(),
)

/** 只读 group_id=3 的 VIP 会员组。 */
private const val PAID_GROUP_KEY = "3"

/** 单集购买的类型位，旧调用恒传 "4"。 */
private const val BUY_EPISODE_TYPE = "4"

/** 模块 id，与试看接口一致，恒传 "1"。 */
private const val BUY_EPISODE_MODULE_ID = "1"

/** 播放源下标 0 起，后端 sid 1 起（夹具 `v1_vod_detail.json` 首个源 sid=1）。 */
private const val SOURCE_SID_OFFSET = 1
