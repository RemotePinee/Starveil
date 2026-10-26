package com.starveil.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.WithdrawRepository
import com.starveil.core.model.WithdrawChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 金币提现页状态持有者。只调 [WithdrawRepository]，不发 HTTP（AGENTS.md 第 1 节）。
 *
 * 与历史页面一一对应：
 * - `init` 拉一次 `v1.user/goldTip`（`:97-108`）；
 * - [submit] 做旧页那四处判空（`:167-183`），再下发 `v1.user/goldWithdrawApply`；
 * - 提交成功只表示"申请已提交"，旧页随后刷新提现记录（`:181`），
 * 新版没有记录页，改为常驻 [WithdrawUiState.submitted] 成功态。
 *
 * 校验全部留在本地：金额非整数、低于门槛、收款信息为空都不打接口。
 *
 * 申请不可重放：结果未知（断网/超时/5xx/解析失败）时进入 [WithdrawUiState.pendingConfirm]，
 * 禁止再次提交，直到用户主动确认。服务端 `POST v1.user/goldWithdrawApply` 目前没有幂等键字段
 * （ 只收
 * num/type/account/realname/qrcode），客户端只能靠自己把重复提交挡住；
 * 服务端补上 `request_id` + 唯一索引后，在仓库层透传 [WithdrawUiState.requestId] 即可。
 */
class WithdrawViewModel(
    private val repository: WithdrawRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<WithdrawUiState> = _state.asStateFlow()

    private val _events = Channel<WithdrawEvent>(Channel.BUFFERED)
    val events: Flow<WithdrawEvent> = _events.receiveAsFlow()

    /** 首帧：提现说明有快照就直接铺出来，表单不必先整屏转圈。 */
    private fun initialState(): WithdrawUiState {
        val tips = repository.cachedTips() ?: return WithdrawUiState()
        return WithdrawUiState(tips = tips, isLoading = false)
    }

    init {
        loadTips()
    }

    /** 说明文案加载失败后的重试。 */
    fun retry() = loadTips()

    fun onAmountChange(value: String) = _state.update { it.copy(amount = value) }

    fun onAccountChange(value: String) = _state.update { it.copy(account = value) }

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }

    fun onChannelSelected(channel: WithdrawChannel) = _state.update { it.copy(channel = channel) }

    /**
     * 提交提现申请。
     *
     * 四道本地闸门：结果未知的锁定、金额格式、金额门槛/余额、收款信息。任一不过只提示，
     * 不打接口——旧页也是先判空再发请求。
     */
    fun submit() {
        val current = _state.value
        val amountError = current.amountError
        when {
            current.isSubmitting || current.submitted -> Unit
            current.pendingConfirm != null -> notify(PENDING_CONFIRM_MESSAGE)
            amountError != null -> notify(amountError)
            !current.canSubmit -> notify("请填写收款账号与姓名")
            else -> startApply(current)
        }
    }

    private fun startApply(current: WithdrawUiState) {
        val amount = current.amountValue ?: return
        sendApply(
            WithdrawRequest(
                amount = amount,
                channel = current.channel,
                account = current.account.trim(),
                name = current.name.trim(),
                qrcode = current.qrcodePath,
            ),
        )
    }

    /** 真正下发申请：结果未知时保留 [WithdrawUiState.requestId]，重试沿用同一个键。 */
    private fun sendApply(request: WithdrawRequest) {
        val requestId = _state.value.requestId.ifBlank { UUID.randomUUID().toString() }
        _state.update { it.copy(isSubmitting = true, requestId = requestId) }
        viewModelScope.launch {
            val result = repository.apply(
                amount = request.amount,
                channel = request.channel,
                account = request.account,
                name = request.name,
                qrcode = request.qrcode,
            )
            when (result) {
                is AppResult.Success -> onApplySuccess()
                is AppResult.Failure -> onApplyFailure(request, result.error)
            }
        }
    }

    private fun onApplySuccess() = _state.update {
        it.copy(isSubmitting = false, submitted = true, amount = "", pendingConfirm = null, requestId = "")
    }

    private fun onApplyFailure(request: WithdrawRequest, error: AppError) {
        if (error.isOutcomeUnknown()) {
            val message = "${error.toPageMessage()}；$PENDING_CONFIRM_MESSAGE"
            _state.update {
                it.copy(isSubmitting = false, pendingConfirm = PendingWithdraw(request, message))
            }
            notify(message)
            return
        }
        // 后端明确拒绝（code != 200）：申请没落库，幂等键作废，用户改完可以重新提交
        _state.update { it.copy(isSubmitting = false, requestId = "") }
        notify(error.toPageMessage())
    }

    /**
     * 用户已查询并确认：解除锁定，按原请求、原幂等键重试。
     *
     * 保留 `requestId` 是关键——服务端支持幂等键后，这次重试不会变成第二笔申请。
     * 表单一起恢复成原请求，避免界面显示的金额/收款人与实际重发的请求对不上。
     */
    fun onPendingConfirmed() {
        val current = _state.value
        val pending = current.pendingConfirm ?: return
        if (current.isSubmitting) return
        val request = pending.request
        _state.update {
            it.copy(
                pendingConfirm = null,
                amount = request.amount.toString(),
                channel = request.channel,
                account = request.account,
                name = request.name,
                qrcodePath = request.qrcode,
                qrcodePreview = if (request.qrcode == it.qrcodePath) it.qrcodePreview else "",
            )
        }
        sendApply(request)
    }

    /**
     * 这次失败是否「结果未知」。
     *
     * [AppError.Business] 是后端明确拒绝（响应壳 `code != 200`），申请一定没落库；
     * 断网、超时、HTTP 层错误、响应解析失败都可能是"请求已到达、响应没回来"，
     * 一律按未知处理——宁可让用户多确认一次，也不能重复提交生成第二笔申请。
     */
    private fun AppError.isOutcomeUnknown(): Boolean = this !is AppError.Business

    /**
     * 上传收款码：先占位 [WithdrawUiState.uploadingQrcode]，成功后写回服务端路径。
     * 失败只提示，不动已经填好的表单。
     */
    fun onQrcodePicked(pick: QrcodePick) {
        if (_state.value.uploadingQrcode) return
        _state.update { it.copy(uploadingQrcode = true) }
        viewModelScope.launch { uploadQrcode(pick) }
    }

    /** 移除收款码：提交时就不带 `qrcode` 字段。 */
    fun onQrcodeRemoved() = _state.update { it.copy(qrcodePath = "", qrcodePreview = "") }

    /** 相册里的图读不出来（超大 / 损坏 / 无权限）时提示，不能让按钮点了没反应。 */
    fun onQrcodePickFailed() = notify("图片读取失败，请换一张试试")

    private suspend fun uploadQrcode(pick: QrcodePick) {
        when (val result = repository.uploadQrcode(pick.bytes, pick.fileName, pick.mime)) {
            is AppResult.Success -> _state.update {
                it.copy(uploadingQrcode = false, qrcodePath = result.value, qrcodePreview = pick.uri)
            }

            is AppResult.Failure -> {
                _state.update { it.copy(uploadingQrcode = false) }
                notify(result.error.toPageMessage())
            }
        }
    }

    private fun loadTips() {
        // 已有说明时不打回骨架屏：重试只为刷新，页面保持上一帧
        _state.update { it.copy(isLoading = it.tips == null, loadError = null) }
        viewModelScope.launch {
            when (val result = repository.tips()) {
                is AppResult.Success -> _state.update {
                    it.copy(isLoading = false, tips = result.value, loadError = null)
                }

                is AppResult.Failure -> _state.update { current ->
                    // 有旧说明就保留：整页错误态会把已经能用的表单顶掉
                    current.copy(
                        isLoading = false,
                        loadError = if (current.tips == null) result.error.toPageMessage() else null,
                    )
                }
            }
        }
    }

    private fun notify(text: String) {
        viewModelScope.launch { _events.send(WithdrawEvent.Message(text)) }
    }

    companion object {

        fun factory(repository: WithdrawRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { WithdrawViewModel(repository) }
        }
    }
}
