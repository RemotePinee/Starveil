package com.starveil.feature.user

import com.starveil.core.model.MembershipStatus
import com.starveil.core.model.UserProfile
import com.starveil.core.model.toMembershipStatus

/** 资料页的一个数值格（影豆 / 积分 / 金币）。 */
data class ProfileStat(val label: String, val value: String)

/**
 * 个人资料 / 设置页状态。
 *
 * @param apiHost 接口域名：后端头像存的是站内相对路径（`/upload/avatar/x.jpg`），
 *   要拼上它才能请求；域名来自 `strings.xml`，代码里不写死。
 * @param versionName 版本号，由 `:app` 传进来（feature 模块读不到 app 的 BuildConfig）。
 * @param isSaving 昵称保存中，用来禁用「保存」避免连点
 */
data class ProfileUiState(
    val profile: UserProfile? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isLoggedIn: Boolean = true,
    val isLoggingOut: Boolean = false,
    val isSaving: Boolean = false,
    val isUploading: Boolean = false,
    val apiHost: String = "",
    val versionName: String = "",
) {

    val needsLogin: Boolean
        get() = !isLoggedIn

    val displayName: String
        get() = profile?.nickName?.takeIf { it.isNotBlank() }
            ?: profile?.userName?.takeIf { it.isNotBlank() }
            ?: "未登录"

    /** 可编辑的昵称原值（DTO 已把 urlencode 的昵称解码）。 */
    val nickName: String
        get() = profile?.nickName.orEmpty()

    val userName: String
        get() = profile?.userName.orEmpty()

    val userId: String
        get() = profile?.userId?.takeIf { it > 0 }?.toString().orEmpty()

    val avatarUrl: String?
        get() {
            val raw = profile?.portrait?.takeIf { it.isNotBlank() } ?: return null
            return when {
                raw.startsWith(ABSOLUTE_URL_PREFIX) -> raw
                raw.startsWith("/") && apiHost.isNotBlank() -> apiHost.trimEnd('/') + raw
                else -> null
            }
        }

    val groupLabel: String
        get() = profile?.groupName?.takeIf { it.isNotBlank() } ?: "未分组"

    val levelLabel: String
        get() = profile?.level?.takeIf { it.isNotBlank() } ?: "普通"

    val isMember: Boolean
        get() = profile?.isVip == true

    /** 会员状态文案（未登录 / 未开通 / 到期日），与会员页共用 [MembershipStatus]。 */
    val membershipLabel: String
        get() = profile.toMembershipStatus(isLoggedIn).label

    val stats: List<ProfileStat>
        get() = listOf(
            ProfileStat(label = "影豆", value = (profile?.points ?: 0).toString()),
            ProfileStat(label = "积分", value = (profile?.userPoints ?: 0).toString()),
            ProfileStat(label = "金币", value = profile?.gold?.takeIf { it.isNotBlank() } ?: ZERO),
        )
}

private const val ABSOLUTE_URL_PREFIX = "https://"

private const val ZERO = "0"
