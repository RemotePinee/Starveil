package com.starveil.feature.pay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.common.getOrNull
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.PayRepository
import com.starveil.core.data.StartupRepository
import com.starveil.core.data.UserRepository
import com.starveil.core.model.PayPlan
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 支付页状态持有者。只调仓库，不发 HTTP（AGENTS.md 第 3 节）。
 *
 * 三条入口对应的三处调用：
 * 1. 会员/充值下单：（下单拿 order_code，再拼 H5 地址）；
 * 2. 卡密兑换：；
 * 3. 单集购买：（旧页买完直接 toast，这里上抛事件）。
 *
 * 订单收口与两条短流程拆在 [PayOrderFlow] / [PayPurchaseFlow]，本类只管状态与事件；
 * 回跳由宿主在 onResume 调 [onReturnedFromPayment]，不轮询。
 */
class PayViewModel(
    private val payRepository: PayRepository,
    private val startupRepository: StartupRepository,
    /** 本地会话：Hero 靠它区分"未登录"和"资料还没到"，不靠 profile 是否为 null 去猜。 */
    private val authRepository: AuthRepository,
    /**
     * 用户资料仓库：卡密兑换成功后刷新一次，让会员到期/金币是最新值。
     * 为 null 表示调用方没接线，此时只跳过刷新，不影响兑换本身（同 `UserCenterViewModel`）。
     */
    private val userRepository: UserRepository? = null,
    private val args: PayArgs = PayArgs(),
) : ViewModel() {

    private val orderFlow = PayOrderFlow(payRepository)
    private val purchaseFlow = PayPurchaseFlow(payRepository)

    private val _state = MutableStateFlow(payInitialState(args, payRepository))
    val state: StateFlow<PayUiState> = _state.asStateFlow()

    private val _events = Channel<PayEvent>(Channel.BUFFERED)
    val events: Flow<PayEvent> = _events.receiveAsFlow()


    init {
        reload()
    }

    /** 整页错误态的重试。 */
    fun retry() = reload()

    fun onPlanSelected(planId: Int) = _state.update { it.copy(selectedPlanId = planId) }

    fun onPaymentSelected(code: String) = _state.update { it.copy(selectedPaymentCode = code) }

    fun onCardCodeChange(value: String) = _state.update { it.copy(cardCode = value) }

    /** 底部按钮：单集模式直接买单集，会员模式走"下单 + 上抛 H5 地址"。 */
    fun submit() {
        val current = _state.value
        when {
            current.isSubmitting -> Unit
            current.isEpisodeMode -> buyEpisode()
            else -> submitOrder(current)
        }
    }

    /** 单集购买。 */
    fun buyEpisode() {
        val current = _state.value
        when {
            current.isSubmitting -> Unit
            current.args.vodId <= 0 || current.args.episodeNumber <= 0 ->
                notify("缺少影片或集数信息，无法购买")

            else -> launchPurchase { purchaseFlow.buyEpisode(current.args) }
        }
    }

    /**
     * 卡密兑换。
     *
     * 输入先 trim + 转大写：后台 `card_no` 是大写十六进制，用户手输小写/带空格
     * 不该白白消耗一次核销机会。空值与超长在本地拦掉，不打接口。
     */
    fun redeemCard() {
        val current = _state.value
        val code = current.cardCode.trim().uppercase()
        when {
            current.isSubmitting -> Unit
            code.isEmpty() -> notify("请输入卡密")
            code.length > MAX_CARD_LENGTH -> notify("卡密格式不正确，请核对后重试")
            else -> submitCard(code)
        }
    }

    /**
     * 回到前台时同步会话。
     *
     * 会员页是底栏常驻页面，退出登录后 ViewModel 不会重建；不同步的话 Hero 会一直挂着
     * 上一个账号的状态。只在登录态真的变了时重拉资料，避免每次切 tab 都打一次接口。
     */
    fun syncSession() {
        viewModelScope.launch {
            val loggedIn = authRepository.restore() != null
            if (loggedIn == _state.value.isLoggedIn) return@launch
            _state.update { it.copy(isLoggedIn = loggedIn, profile = null) }
            if (loggedIn) refreshProfile()
        }
    }
    /** 回跳结算入口（宿主 onResume 调用一次）。靠 `isCheckingOrder` + 清订单号防重复。 */
    fun onReturnedFromPayment() {
        val current = _state.value
        val orderCode = current.pendingOrderCode ?: return
        if (current.isCheckingOrder) return

        _state.update { it.copy(isCheckingOrder = true) }
        val plan = current.plans.firstOrNull { it.id == current.pendingPlanId }
        viewModelScope.launch {
            when (val result = orderFlow.settle(orderCode, plan)) {
                is SettlementResult.Failed -> _state.update {
                    // 查单失败不再自动重查（ onError 也直接清单号）
                    it.copy(isCheckingOrder = false, pendingOrderCode = null, lastOrderMessage = result.message)
                }

                is SettlementResult.Settled -> _state.update {
                    it.copy(
                        isCheckingOrder = false,
                        pendingOrderCode = null,
                        orderStatus = result.status,
                        membershipActivated = result.activated,
                        lastOrderMessage = result.message,
                    )
                }
            }
        }
    }

    private fun submitOrder(current: PayUiState) {
        val plan = current.selectedPlan
        val payment = current.selectedPaymentCode
        if (plan == null || payment == null) {
            notify("请选择套餐与支付方式")
            return
        }
        startOrder(plan, payment)
    }

    /** 下单成功后记下待支付订单：回跳查单与兑换都要用它（订单号 + 套餐编号）。 */
    private fun startOrder(plan: PayPlan, payment: String) {
        _state.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            when (val result = orderFlow.place(plan, payment)) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            pendingOrderCode = result.value.orderCode,
                            pendingPlanId = plan.id,
                            orderStatus = null,
                            membershipActivated = false,
                            lastOrderMessage = null,
                        )
                    }
                    _events.send(PayEvent.OpenPayment(result.value.payUrl))
                }

                is AppResult.Failure -> failSubmission(result.error)
            }
        }
    }

    private fun launchPurchase(
        onSuccess: String = "购买成功，可以继续观看",
        block: suspend () -> AppResult<Unit>,
    ) {
        _state.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            when (val result = block()) {
                is AppResult.Success -> {
                    _state.update { it.copy(isSubmitting = false, cardCode = "") }
                    notify(onSuccess)
                }

                is AppResult.Failure -> failSubmission(result.error)
            }
        }
    }

    /** 卡密提交流程：成功按卡类型提示，并刷新一次用户资料（失败不打扰用户）。 */
    private fun submitCard(code: String) {
        _state.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            when (val result = purchaseFlow.redeemCard(code)) {
                is AppResult.Success -> {
                    _state.update { it.copy(isSubmitting = false, cardCode = "") }
                    notify(result.value.redeemMessage())
                    refreshProfile()
                }

                is AppResult.Failure -> failSubmission(result.error)
            }
        }
    }

    /** 只刷资料：卡密兑换成功后调用，让会员到期/金币是最新值。 */
    private suspend fun refreshProfile() {
        val repository = userRepository ?: return
        val profile = repository.loadProfile().getOrNull() ?: return
        _state.update { it.copy(profile = profile) }
    }

    private fun reload() {
        // 已有价目表（含快照）时不打回骨架屏：刷新在后台进行，页面保持上一帧
        _state.update { it.copy(isLoading = it.plans.isEmpty(), errorMessage = null) }
        viewModelScope.launch {
            // 本地会话最先读：骨架屏收起时 Hero 已经知道该显示"登录后查看"还是"尚未开通"，
            // 不会先给一句占位再跳变。
            _state.update { it.copy(isLoggedIn = authRepository.restore() != null) }
            loadPayments()
            loadTips()
            loadCatalog()
            // 到期日随后到；读取失败保持 null，Hero 落到"尚未开通"（不弹错，别让人以为没到账）
            refreshProfile()
        }
    }

    /** 支付方式来自启动配置。失败不阻断页面。 */
    private suspend fun loadPayments() {
        val payments = startupRepository.load().getOrNull()?.payments.orEmpty()
        _state.update { current ->
            current.copy(
                payments = payments,
                selectedPaymentCode = current.selectedPaymentCode ?: payments.firstOrNull()?.code,
            )
        }
    }

    /** 充值提示失败不阻断： payTip 失败只是隐藏卡密入口。 */
    private suspend fun loadTips() {
        val tips = payRepository.tips().getOrNull() ?: return
        _state.update { it.copy(tips = tips) }
    }

    private suspend fun loadCatalog() {
        if (args.mode == PayMode.EPISODE) {
            _state.update { it.copy(isLoading = false) }
            return
        }
        when (val result = payRepository.plans()) {
            is AppResult.Success -> _state.update { current ->
                current.copy(
                    isLoading = false,
                    plans = result.value,
                    selectedPlanId = current.selectedPlanId ?: result.value.firstOrNull()?.id,
                    errorMessage = null,
                )
            }

            is AppResult.Failure -> _state.update { current ->
                // 有内容就保留：快照/上次响应比这次的错误更有用，只有空页才切错误态
                if (current.plans.isEmpty()) {
                    current.copy(isLoading = false, errorMessage = result.error.toPayMessage())
                } else {
                    current.copy(isLoading = false)
                }
            }
        }
    }

    private fun failSubmission(error: AppError) {
        _state.update { it.copy(isSubmitting = false) }
        notify(error.toPayMessage())
    }

    private fun notify(text: String) {
        viewModelScope.launch { _events.send(PayEvent.Message(text)) }
    }

    companion object {

        fun factory(
            payRepository: PayRepository,
            startupRepository: StartupRepository,
            userRepository: UserRepository? = null,
            /** 必须接线：没有会话就读不出登录态，Hero 会一直停在占位。 */
            authRepository: AuthRepository,
            args: PayArgs,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PayViewModel(payRepository, startupRepository, authRepository, userRepository, args)
            }
        }
    }
}

/**
 * 首帧状态：会员模式下价目表有快照就直接当"已加载"，冷启动不再整屏转圈。
 *
 * 放在类外是为了让 [PayViewModel] 不超过 detekt 的类体积阈值；它只依赖
 * 导航参数与仓库两份输入，不碰实例状态。单集模式没有价目表，保持骨架屏。
 */
private fun payInitialState(args: PayArgs, repository: PayRepository): PayUiState {
    if (args.mode != PayMode.MEMBERSHIP) {
        return PayUiState(args = args)
    }
    val cached = repository.cachedPlans().orEmpty()
    return if (cached.isEmpty()) {
        PayUiState(args = args)
    } else {
        PayUiState(
            args = args,
            plans = cached,
            selectedPlanId = cached.first().id,
            isLoading = false,
        )
    }
}
