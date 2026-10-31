package com.starveil.feature.pay

import com.starveil.core.common.AppError
import com.starveil.core.model.MembershipStatus
import com.starveil.core.model.PayOrderStatus
import com.starveil.core.model.PayPlan
import com.starveil.core.model.PayTips
import com.starveil.core.model.PaymentMethod
import com.starveil.core.model.UserProfile
import com.starveil.core.model.toMembershipStatus

/**
 * 支付页模式。路由参数 `ARG_PAY_MODE` 只允许两个值，未知值按会员页处理
 * （用 `type` 区分充值/升级）。
 */
enum class PayMode {
    MEMBERSHIP,
    EPISODE;
    companion object {
        fun fromRoute(value: String?): PayMode =
            if (value == PAY_MODE_EPISODE) EPISODE else MEMBERSHIP
    }
}

/**
 * 支付页入参。[PayViewModel] 的构造参数因此不超过 5 个（AGENTS.md 第 2 节）。
 *
 * @param vodId 单集购买用，旧 的 `id`
 * @param episodeNumber 单集 nid，1 起
 * @param sourceIndex 播放源下标，0 起；只在 [sid] 缺失时给仓库兜底
 * @param sid 播放源的后端主键，1 起；0 表示没拿到（老数据/后端没下发），仓库退回下标 + 1
 */
data class PayArgs(
    val mode: PayMode = PayMode.MEMBERSHIP,
    val vodId: Int = 0,
    val episodeNumber: Int = 0,
    val sourceIndex: Int = 0,
    val sid: Int = 0,
)

/**
 * 支付页状态。不可变，通过 `StateFlow` 暴露（AGENTS.md 第 5 节）。
 *
 * @param plans 会员价目表；单集模式下为空
 * @param selectedPlanId [PayPlan.id]，本地编号而不是后端字段
 * @param payments 启动接口下发的支付方式；旧 不过滤 `status`
 * @param tips 充值提示，来自 `v1.user/payTip` + `v1.user/goldTip`（见 `PayRepository.tips`）
 * @param errorMessage 仅用于加载失败（整页可重试）；提交失败的提示走一次性事件
 */
data class PayUiState(
    val args: PayArgs = PayArgs(),
    val plans: List<PayPlan> = emptyList(),
    val selectedPlanId: Int? = null,
    val payments: List<PaymentMethod> = emptyList(),
    val selectedPaymentCode: String? = null,
    val tips: PayTips? = null,
    val cardCode: String = "",
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    /** 待支付/最近一单的订单号；查过状态后清掉，也是查完即置空。 */
    val pendingOrderCode: String? = null,
    /** 下单时选中的套餐编号，回跳后靠它找回 groupId + 时长档位做兑换。 */
    val pendingPlanId: Int? = null,
    /** 订单查询结果；null 表示本页还没查过单。 */
    val orderStatus: PayOrderStatus? = null,
    /** 正在查订单（按钮与提示都靠它防重入，避免回跳被触发两次时打两次接口）。 */
    val isCheckingOrder: Boolean = false,
    /** 会员是否已兑换成功。 */
    val membershipActivated: Boolean = false,
    /** 回跳后的结果文案（未支付 / 支付成功 / 兑换失败），常驻状态而不是一次性事件。 */
    val lastOrderMessage: String? = null,
    /** 用户资料；进页时读一次、卡密兑换后再读一次，Hero 的会员状态靠它。null 表示未登录或读取失败。 */
    val profile: UserProfile? = null,
    /**
     * 本地是否有会话；**null 表示还没读出来**（进页首帧）。
     * Hero 靠它区分"未登录"和"资料还没到"，不会先闪一句"登录后查看会员状态"再跳变。
     */
    val isLoggedIn: Boolean? = null,
) {

    /** 有待支付订单或有结果提示时，页面在套餐上方显示订单区块。 */
    val hasOrderNotice: Boolean
        get() = pendingOrderCode != null || orderStatus != null || lastOrderMessage != null

    val selectedPlan: PayPlan? get() = plans.firstOrNull { it.id == selectedPlanId }

    val isEpisodeMode: Boolean get() = args.mode == PayMode.EPISODE

    /** 会员模式下价目表为空且没有错误：后端没配价。 */
    val isEmpty: Boolean get() = !isEpisodeMode && plans.isEmpty() && !isLoading && errorMessage == null

    /** 会员模式要"套餐 + 支付方式"都选好；单集模式只要有影片与集数。 */
    val canSubmit: Boolean
        get() = !isSubmitting && if (isEpisodeMode) {
            args.vodId > 0 && args.episodeNumber > 0
        } else {
            selectedPlan != null && selectedPaymentCode != null
        }

    /** 底部按钮文案：旧页三个套餐卡片各自下单，这里统一成一个按钮。 */
    val submitLabel: String
        get() {
            val plan = selectedPlan
            return when {
                isEpisodeMode -> "立即兑换"
                plan != null -> "去支付 ¥${plan.priceYuan}"
                else -> "请选择套餐"
            }
        }

    /** 卡密兑换按钮：空输入与提交中都不该发请求，按钮直接置灰。 */
    val canRedeem: Boolean
        get() = !isSubmitting && cardCode.isNotBlank()

    /**
     * 会员状态。判定与文案都来自 [MembershipStatus]（`core/model/MembershipStatus.kt`），
     * 与"我的"页会员卡共用同一份逻辑，不是各写一份。
     */
    val membershipStatus: MembershipStatus
        get() = profile.toMembershipStatus(isLoggedIn)

    /** 星标用会员金还是三级文字色，看它。 */
    val isMember: Boolean
        get() = membershipStatus.isMember

    /** Hero 副标题：未登录 / 未开通 / 到期日，文案由 [MembershipStatus.label] 给。 */
    val memberStatusLabel: String
        get() = membershipStatus.label
}

/** 一次性事件：支付页不自己开浏览器，URL 上抛给 `:app`（AGENTS.md 第 1 节依赖方向）。 */
sealed interface PayEvent {
    data class OpenPayment(val url: String) : PayEvent
    data class Message(val text: String) : PayEvent
}

/** 错误 -> 用户可读文案，与收藏/登录页的写法保持一致，但文案按支付场景收敛。 */
internal fun AppError.toPayMessage(): String = when (this) {
    is AppError.Business -> message.ifBlank { "请求被拒绝，请稍后重试" }
    is AppError.Network -> "网络异常，请检查网络后重试"
    is AppError.Http -> "服务异常（$code）"
    is AppError.Serialization -> "数据解析失败，请稍后重试"
    is AppError.Unknown -> "未知错误，请稍后重试"
}
