package com.starveil.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.AuthRepository
import com.starveil.core.data.UserRepository
import com.starveil.core.model.UserProfile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 资料页一次性事件：退出登录成功、或一次操作的结果提示。 */
sealed interface ProfileEvent {

    data object SignedOut : ProfileEvent

    data class Message(val text: String) : ProfileEvent
}

/**
 * 个人资料 / 设置页状态持有者。
 *
 * 可改的只有后端真正放行的两项：昵称（`POST v1.user/index`）与头像（`POST v1.upload/user`）。
 * 插件 `Users::PROFILE_WHITELIST` 只有 `user_nick_name` / `user_portrait` 两列，
 * 其余字段（手机、邮箱、密码）服务端没有对应路由，页面上就不出现，避免摆一堆点不动的项。
 */
class ProfileViewModel(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    apiHost: String = "",
    versionName: String = "",
    /** 进程内缓存的资料：有它就直接出内容，进页面不再先闪骨架。 */
    initialProfile: UserProfile? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProfileUiState(
            profile = initialProfile,
            isLoading = initialProfile == null,
            apiHost = apiHost,
            versionName = versionName,
        ),
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val _events = Channel<ProfileEvent>(Channel.BUFFERED)
    val events: Flow<ProfileEvent> = _events.receiveAsFlow()

    init {
        load()
    }

    /** 错误态里的"重试"按钮。 */
    fun retry() = load()

    /** 改昵称：长度与空值在前端先挡一次，服务端白名单之外没有校验。 */
    fun saveNickName(raw: String) {
        val name = raw.trim()
        if (_state.value.isSaving) return
        when {
            name.length !in NICK_MIN..NICK_MAX -> send(NICK_LENGTH_HINT)
            name != _state.value.nickName -> submitNickName(name)
        }
    }

    private fun submitNickName(name: String) {
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            when (val result = userRepository.updateNickName(name)) {
                is AppResult.Success -> {
                    _state.update { it.copy(profile = result.value, isSaving = false) }
                    send(NICK_SAVED)
                }
                is AppResult.Failure -> {
                    _state.update { it.copy(isSaving = false) }
                    send(result.error.toPageMessage())
                }
            }
        }
    }

    /** 上传头像：服务端保存后会把新路径写进资料，这里重拉一次让页面立刻换图。 */
    fun uploadAvatar(bytes: ByteArray, fileName: String, mime: String) {
        if (_state.value.isUploading) return
        _state.update { it.copy(isUploading = true) }
        viewModelScope.launch {
            when (val upload = userRepository.uploadAvatar(bytes, fileName, mime)) {
                is AppResult.Success -> {
                    load()
                    _state.update { it.copy(isUploading = false) }
                    send(AVATAR_SAVED)
                }
                is AppResult.Failure -> {
                    _state.update { it.copy(isUploading = false) }
                    send(upload.error.toPageMessage())
                }
            }
        }
    }

    /** 退出登录：先清本地会话，再通知调用方离开本页。 */
    fun logout() {
        if (_state.value.isLoggingOut) return
        _state.update { it.copy(isLoggingOut = true) }
        viewModelScope.launch {
            authRepository.logout()
            _state.update { it.copy(isLoggingOut = false, isLoggedIn = false, profile = null) }
            _events.send(ProfileEvent.SignedOut)
        }
    }

    private fun send(text: String) {
        viewModelScope.launch { _events.send(ProfileEvent.Message(text)) }
    }

    private fun load() {
        // 已经有缓存就静默刷新：只有首次进页面（没有资料）才露出骨架
        _state.update { it.copy(isLoading = it.profile == null, errorMessage = null) }
        viewModelScope.launch {
            if (authRepository.restore() == null) {
                _state.update { it.copy(isLoading = false, isLoggedIn = false) }
                return@launch
            }
            when (val result = userRepository.loadProfile()) {
                is AppResult.Success -> applyProfile(result.value)
                is AppResult.Failure -> applyFailure(result.error)
            }
        }
    }

    private fun applyProfile(profile: UserProfile) {
        _state.update {
            it.copy(profile = profile, isLoading = false, isLoggedIn = true, errorMessage = null)
        }
    }

    private fun applyFailure(error: AppError) {
        if (error.isUnauthorized()) {
            _state.update { it.copy(isLoading = false, isLoggedIn = false, errorMessage = null) }
            return
        }
        _state.update { it.copy(isLoading = false, errorMessage = error.toPageMessage()) }
    }

    companion object {
        fun factory(
            userRepository: UserRepository,
            authRepository: AuthRepository,
            apiHost: String = "",
            versionName: String = "",
            initialProfile: UserProfile? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProfileViewModel(
                    userRepository = userRepository,
                    authRepository = authRepository,
                    apiHost = apiHost,
                    versionName = versionName,
                    initialProfile = initialProfile,
                )
            }
        }
    }
}

/**
 * 后端未登录固定回 `code=403`（见 `UserRepository` 的契约说明）。
 * 这属于登录态失效，不能当网络错误让用户反复重试。
 */
private fun AppError.isUnauthorized(): Boolean =
    (this as? AppError.Business)?.code == HTTP_UNAUTHORIZED

private const val HTTP_UNAUTHORIZED = 403
private const val NICK_MIN = 2
private const val NICK_MAX = 20
private const val NICK_LENGTH_HINT = "昵称请控制在 2-20 个字"
private const val NICK_SAVED = "昵称已更新"
private const val AVATAR_SAVED = "头像已更新"
