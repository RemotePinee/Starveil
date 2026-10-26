package com.starveil.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.PointsRepository
import com.starveil.core.data.UserRepository
import com.starveil.core.model.Session
import com.starveil.core.model.ShareLink
import com.starveil.core.model.UserCenterVariant
import com.starveil.core.model.UserProfile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SHARE_FAILED = "海报生成失败，稍后重试"

/** 面板拉不起来：设备上没有能接收 `ACTION_SEND` 的应用，与"海报生成失败"分开说。 */
private const val SHARE_NO_TARGET = "没有可用的分享应用，请稍后重试"
/**
 * 一次性事件走 Channel，不塞进 UiState）。
 */
data class UserCenterEvent(val text: String, val asToast: Boolean = false)

/**
 * 用户中心状态持有者。会话读 [AuthRepository]，资料读 [UserRepository]。
 *
 * 与的差别：旧 6 个 Fragment 各自在 `onResume` 里读一遍 SP 再手改控件可见性，
 * 数值还散落在各处手动 setText；这里只把会话和一份资料读进 [UserCenterUiState]，
 * 可见性差异由 [UserCenterLayouts] 的配置表达，数值由 [UserCenterUiState.values] 提供。
 *
 * @param variant 皮肤变体，由 `:app` 用 `Skins.userCenter(skin)` 算好后注入
 * @param userRepository 资料仓库；为 null 表示调用方还没接线，此时不发资料请求，
 *   三栏数值停在占位符（页面结构与皮肤不受影响）
 */
class UserCenterViewModel(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository? = null,
    private val pointsRepository: PointsRepository? = null,
    private val variant: UserCenterVariant = UserCenterVariant.A,
    private val memberEnabled: Boolean = true,
    /** 冷启动进页时 cookie 里已有的会话：首帧就按登录态渲染，不会先闪一下"未登录"。 */
    initialSession: Session? = null,
    initialProfile: UserProfile? = null,
    apiHost: String = "",
) : ViewModel() {

    private val _state = MutableStateFlow(
        UserCenterUiState(
            variant = variant,
            session = initialSession,
            profile = initialProfile,
            isLoading = initialSession == null,
            memberEnabled = memberEnabled,
            apiHost = apiHost,
        ),
    )
    val state: StateFlow<UserCenterUiState> = _state.asStateFlow()

    private val _events = Channel<UserCenterEvent>(Channel.BUFFERED)
    val events: Flow<UserCenterEvent> = _events.receiveAsFlow()

    /** 分享信息到位后交给页面去拼海报（Channel 不重放，旋屏不会重复弹分享面板）。 */
    private val shareRequests = Channel<ShareLink>(Channel.BUFFERED)
    val shareLinks: Flow<ShareLink> = shareRequests.receiveAsFlow()

    /** 分享面板是否已经拉起：只在 `startActivity` 成功后置位，拉起失败不发奖。 */
    private var shareLaunched = false

    private val _shareConfirm = MutableStateFlow(false)

    /**
     * 回到前台后是否要弹一次「分享完成了吗」。
     *
     * chooser 没有可靠的结果回调（见 `SharePoster.share` 的注释），"拉起即发奖"
     * 会把用户点开又取消也算成分享成功；改成回到前台再确认一次，确认了才发。
     */
    val shareConfirm: StateFlow<Boolean> = _shareConfirm.asStateFlow()

    init {
        load()
    }

    /** 退出登录。只清本地 SP，不请求 `/v1.auth/logout`（见 DefaultAuthRepository）。 */
    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _state.update { it.copy(session = null, profile = null, errorMessage = null) }
        }
    }

    /** 下拉刷新/重新进入页面：先重读会话，有会话再重拉资料。 */
    fun refresh() = load()

    /**
     * 回到前台时同步会话。
     *
     * 登录页与用户中心在同一个 NavHost 里，登录成功后只是 `popBackStack()`：
     * 用户中心的 ViewModel 没有被重建，`init { load() }` 不会再跑一遍，
     * 于是页面停在"未登录"——这正是"重登了还是这样"的原因。
     * 只在会话真的变了（登录/退出）时才重拉资料，避免每次切页都打一次接口。
     */
    fun syncSession() {
        viewModelScope.launch {
            val session = authRepository.restore()
            if (session?.userId != _state.value.session?.userId) {
                if (session == null) {
                    _state.update { it.copy(session = null, profile = null, errorMessage = null) }
                } else {
                    _state.update { it.copy(session = session) }
                    fetchProfile()
                }
                return@launch
            }
            // 会话没变：先把仓库缓存同步过来（设置页改了昵称/头像立刻生效），
            // 再静默向服务端拉一次——签到、领任务、买断单集都会改积分，
            // 只信缓存的话"我的"页会一直停在旧数字。
            val cached = userRepository?.cachedProfile()
            if (cached != null && cached != _state.value.profile) {
                _state.update { it.copy(profile = cached) }
            }
            fetchProfileQuietly()
        }
    }

    /**
     * 回到前台：同步会话 + 处理「待确认的分享奖励」。
     *
     * 分享面板是另一个 Activity，用户取消回来同样会触发 ON_RESUME，
     * 所以这里只能问一句，不能把"回到前台"当成分享成功的证据。
     */
    fun onResumed() {
        syncSession()
        if (!shareLaunched) return
        shareLaunched = false
        _shareConfirm.value = true
    }

    private fun load() {
        viewModelScope.launch {
            val session = authRepository.restore()
            if (session == null) {
                _state.update { it.copy(session = null, profile = null, isLoading = false) }
                return@launch
            }
            _state.update { it.copy(session = session, isLoading = false) }
            fetchProfile()
        }
    }

    private suspend fun fetchProfile() {
        val repository = userRepository ?: return
        when (val result = repository.loadProfile()) {
            is AppResult.Success -> _state.update { it.copy(profile = result.value, errorMessage = null) }
            is AppResult.Failure -> applyFailure(result.error)
        }
    }

    /** 静默刷新：失败就保留上一次的数值，不弹错误、不清空资料。 */
    private suspend fun fetchProfileQuietly() {
        val repository = userRepository ?: return
        val result = repository.loadProfile()
        if (result is AppResult.Success) {
            _state.update { it.copy(profile = result.value, errorMessage = null) }
        }
    }

    /**
     * 失败后 [UserCenterUiState.profile] 置空，数值自动变占位符；同时把文案写进
     */
    private suspend fun applyFailure(error: AppError) {
        val message = error.toPageMessage()
        _state.update { it.copy(profile = null, errorMessage = message) }
        _events.send(UserCenterEvent(text = message))
    }

    /** 分享：先拿后台配的标题 / 描述 / 链接，海报由页面画。 */
    fun share() {
        val repository = pointsRepository ?: return
        viewModelScope.launch {
            when (val link = repository.shareLink()) {
                is AppResult.Success -> shareRequests.send(link.value)
                is AppResult.Failure -> _events.send(UserCenterEvent(text = link.error.toPageMessage()))
            }
        }
    }

    /** 用户已确认分享发出后领分享积分；服务端每天只发一次，返回 0 表示今天已经领过。 */
    fun onShared() {
        val repository = pointsRepository ?: return
        viewModelScope.launch {
            when (val score = repository.shareScore()) {
                is AppResult.Success -> _events.send(
                    UserCenterEvent(
                        text = if (score.value > 0) "分享成功，积分 +${score.value}" else "今日分享奖励已领取",
                        asToast = true,
                    ),
                )
                is AppResult.Failure -> _events.send(UserCenterEvent(text = score.error.toPageMessage()))
            }
        }
    }

    /** 分享面板真的拉起来了（`SharePoster.share` 返回 true）：只记待发放，不发奖。 */
    fun onShareLaunched() {
        shareLaunched = true
    }

    /** 用户确认已分享：发奖。 */
    fun confirmShare() {
        _shareConfirm.value = false
        onShared()
    }

    /** 用户表示还没发出去：不发奖。 */
    fun dismissShareConfirm() {
        _shareConfirm.value = false
    }

    fun notifyShareFailed() {
        viewModelScope.launch { _events.send(UserCenterEvent(text = SHARE_FAILED)) }
    }

    /** 分享面板拉不起来：不发奖，只提示。 */
    fun notifyShareLaunchFailed() {
        viewModelScope.launch { _events.send(UserCenterEvent(text = SHARE_NO_TARGET)) }
    }
    companion object {

        fun factory(
            authRepository: AuthRepository,
            variant: UserCenterVariant,
            userRepository: UserRepository? = null,
            pointsRepository: PointsRepository? = null,
            /** 会员体系开关；关闭时会员卡换成弱化样式，但卡片不消失。 */
            memberEnabled: Boolean = true,
            initialSession: Session? = null,
            initialProfile: UserProfile? = null,
            apiHost: String = "",
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                UserCenterViewModel(
                    authRepository = authRepository,
                    userRepository = userRepository,
                    pointsRepository = pointsRepository,
                    variant = variant,
                    memberEnabled = memberEnabled,
                    initialSession = initialSession,
                    initialProfile = initialProfile,
                    apiHost = apiHost,
                )
            }
        }
    }
}
