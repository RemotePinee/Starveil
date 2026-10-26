package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.User
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 头像块：头像、昵称、副标题、会员标签。
 *
 * 整块可点，去处由 :app 注入的 [onLoginClick] 决定：未登录跳登录页，已登录跳「个人资料」。
 * 不按登录态关掉点击——已登录用户也想点头像看自己的资料。
 */
@Composable
internal fun UserProfileBlock(
    state: UserCenterUiState,
    onLoginClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLoginClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        ProfileAvatar(
            avatarUrl = state.avatarUrl,
            contentDescription = if (state.isLoggedIn) state.displayName else "未登录",
        )
        Column(
            modifier = Modifier.weight(WEIGHT_FULL),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = if (state.isLoggedIn) "Hello，${state.displayName}" else "Hello，登录 / 注册",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (state.isLoggedIn) state.subtitle else "登录后同步收藏、观看记录与会员权益",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.isLoggedIn) {
                MembershipLine(state = state)
            }
        }
    }
}

@Composable
private fun MembershipLine(state: UserCenterUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        StatusBadge(
            text = if (state.isMember) BADGE_MEMBER else BADGE_NOT_MEMBER,
            highlighted = state.isMember,
        )
        if (state.isMember) {
            Text(
                text = state.memberExpiryLabel,
                style = MaterialTheme.typography.labelSmall,
                color = starveilExtraColors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
@Composable
private fun StatusBadge(text: String, highlighted: Boolean) {
    val color = if (highlighted) starveilExtraColors.brandGold else starveilExtraColors.textFaint
    val background = if (highlighted) {
        starveilExtraColors.brandGold.copy(alpha = BADGE_ALPHA)
    } else {
        starveilExtraColors.chipBackground
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(AppShape.full)
            .background(background)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
    )
}

@Composable
private fun ProfileAvatar(avatarUrl: String?, contentDescription: String) {
    Box(
        modifier = Modifier
            .size(PROFILE_AVATAR)
            .clip(AppShape.full)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = AVATAR_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PhosphorIcons.Regular.User,
            contentDescription = contentDescription,
            tint = starveilExtraColors.textMuted,
            modifier = Modifier.size(IconSize.lg),
        )
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private val PROFILE_AVATAR = Spacing.xxl * 2
private const val AVATAR_ALPHA = 0.16f
private const val BADGE_ALPHA = 0.18f
private const val BADGE_MEMBER = "会员"
private const val BADGE_NOT_MEMBER = "未开通会员"
private const val WEIGHT_FULL = 1f

