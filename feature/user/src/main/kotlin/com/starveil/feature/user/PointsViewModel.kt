package com.starveil.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.PointsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 积分中心：签到 + 今日任务。
 *
 * 每次领取成功都重新拉一次 `sign`/`task`，以服务端为准刷新余额与状态——
 * 客户端不做本地加减，避免和后端 `user_points` 出现两份数。
 */
class PointsViewModel(private val repository: PointsRepository) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<PointsUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** 一次性提示（签到/领取结果），由页面用 Snackbar 展示。 */
    val messages = _messages.receiveAsFlow()

    /**
     * 在飞的刷新任务与代次。
     *
     * `init`、签到、领取、下拉都会发刷新；旧响应晚到就会整块覆盖 `points/tasks`，
     * 表现为"积分倒退、任务重新变可领"。两件保险：
     * - 新一次刷新先取消旧任务，不再白跑三个请求；
     * - 代次对不上直接丢响应——仓库实现不保证响应取消（同步返回、测试里的 fake 都是），
     *   取消之后旧协程仍可能执行到下面的 `_state.update`。
     */
    private var refreshJob: Job? = null
    private var refreshGeneration = 0

    init {
        refresh()
    }

    /**
     * 首帧状态：有快照就直接铺余额与任务列表，冷启动不再从 0 分跳变。
     * 签到由顶部按钮承担，过滤口径与 [refresh] 保持一致。
     */
    private fun initialState(): PointsUiState {
        val cached = repository.cachedPoints() ?: return PointsUiState()
        return PointsUiState(
            points = cached.status.points,
            signedToday = cached.status.signedToday,
            signPoints = cached.status.signPoints,
            tasks = cached.tasks.filterNot { it.key == SIGN_KEY },
            isLoading = false,
        )
    }

    fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        // 已有内容时不打回骨架屏：刷新在后台进行，页面保持上一帧
        _state.update { if (it.hasContent) it else it.copy(isLoading = true) }
        refreshJob = viewModelScope.launch {
            val status = repository.status()
            val tasks = repository.tasks()
            val share = repository.shareLink()
            // 已经被更新的一次刷新取代：整块丢弃，界面只接受最新一次的数据
            if (generation != refreshGeneration) return@launch
            _state.update { current ->
                when {
                    status is AppResult.Failure -> current.copy(
                        isLoading = false,
                        errorMessage = status.error.toPointsMessage(),
                    )

                    tasks is AppResult.Failure -> current.copy(
                        isLoading = false,
                        errorMessage = tasks.error.toPointsMessage(),
                    )

                    else -> current.copy(
                        isLoading = false,
                        errorMessage = null,
                        points = (status as AppResult.Success).value.points,
                        signedToday = status.value.signedToday,
                        signPoints = status.value.signPoints,
                        // 签到由顶部按钮承担，列表里不再重复一行
                        tasks = (tasks as AppResult.Success).value.filterNot { it.key == SIGN_KEY },
                        shareUrl = (share as? AppResult.Success)?.value?.url.orEmpty(),
                        shareTitle = (share as? AppResult.Success)?.value?.title.orEmpty(),
                    )
                }
            }
        }
    }

    fun sign() {
        if (!_state.value.canSign) return
        submit(SIGN_KEY) { repository.signIn() }
    }

    fun claim(key: String) {
        if (key == SIGN_KEY) {
            sign()
            return
        }
        submit(key) {
            if (key == SHARE_KEY) repository.shareScore() else repository.claim(key)
        }
    }

    /** 提交一次领取：成功提示 +N 积分并重新拉状态；失败只提示、不改余额。 */
    private fun submit(key: String, action: suspend () -> AppResult<Int>) {
        if (_state.value.submitting != null) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = key) }
            when (val result = action()) {
                is AppResult.Success -> {
                    val gained = result.value
                    _messages.trySend(if (gained > 0) "获得 $gained 积分" else "今天已经领过了")
                    _state.update { it.copy(submitting = null) }
                    refresh()
                }

                is AppResult.Failure -> {
                    _state.update { it.copy(submitting = null) }
                    _messages.trySend(result.error.toPointsMessage())
                }
            }
        }
    }

    companion object {
        fun factory(repository: PointsRepository) = viewModelFactory {
            initializer { PointsViewModel(repository) }
        }
    }
}

internal const val SIGN_KEY = "sign"

internal const val SHARE_KEY = "share"

/** 积分中心的错误文案：业务错误用后端 msg，其余按网络/服务分类。 */
internal fun AppError.toPointsMessage(): String = when (this) {
    is AppError.Business -> message.ifBlank { "操作失败，请重试" }
    is AppError.Network -> "网络异常，请稍后重试"
    is AppError.Http -> "服务异常（$code）"
    is AppError.Serialization -> "数据解析失败"
    is AppError.Unknown -> "未知错误"
}
