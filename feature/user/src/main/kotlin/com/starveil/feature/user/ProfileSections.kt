package com.starveil.feature.user

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Camera
import com.starveil.core.designsystem.AppearancePickerSheet
import com.starveil.core.designsystem.LocalAppearanceSelection
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/** 账号卡：头像 + 昵称（可改）+ 只读的用户名与 ID。 */
@Composable
internal fun ProfileAccountCard(
    state: ProfileUiState,
    onEditNick: () -> Unit,
    onPickAvatar: () -> Unit,
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Avatar(state = state, onClick = onPickAvatar)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.membershipLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isMember) {
                        starveilExtraColors.brandGold
                    } else {
                        starveilExtraColors.textMuted
                    },
                )
            }
        }
        SettingsDivider()
        SettingsRow(label = "昵称", value = state.nickName.ifBlank { "未设置" }, onClick = onEditNick)
        SettingsDivider()
        SettingsRow(label = "用户名", value = state.userName.ifBlank { PLACEHOLDER })
        SettingsDivider()
        SettingsRow(label = "用户 ID", value = state.userId.ifBlank { PLACEHOLDER })
    }
}

/** 会员信息：状态与等级，只读。 */
@Composable
internal fun ProfileInfoCard(state: ProfileUiState) {
    SettingsCard {
        SettingsRow(label = "会员状态", value = state.membershipLabel)
        SettingsDivider()
        SettingsRow(label = "会员等级", value = "${state.groupLabel} · Lv.${state.levelLabel}")
    }
}

/** 通用：外观（浅色 / 深色 / 跟随系统）、图片缓存（真清理）与版本号。 */
@Composable
internal fun ProfileAppCard(versionName: String) {
    val context = LocalContext.current
    var cacheText by remember { mutableStateOf(imageCacheText(context)) }
    // 外观是本地偏好，与账号无关：未登录也能改，所以不走服务端
    val appearance = LocalAppearanceSelection.current
    var pickingAppearance by remember { mutableStateOf(false) }
    SettingsCard {
        SettingsRow(
            label = "外观",
            value = appearance.current.displayName,
            action = "切换",
            onClick = { pickingAppearance = true },
        )
        SettingsDivider()
        SettingsRow(
            label = "图片缓存",
            value = cacheText,
            action = "清理",
            onClick = {
                clearImageCache(context)
                cacheText = imageCacheText(context)
            },
        )
        SettingsDivider()
        SettingsRow(label = "当前版本", value = versionName.ifBlank { PLACEHOLDER })
    }
    if (pickingAppearance) {
        AppearancePickerSheet(
            current = appearance.current,
            onSelect = { mode ->
                appearance.select(mode)
                pickingAppearance = false
            },
            onDismiss = { pickingAppearance = false },
        )
    }
}

@Composable
private fun Avatar(state: ProfileUiState, onClick: () -> Unit) {
    Box(modifier = Modifier.size(AVATAR_SIZE)) {
        // clip 在 clickable 之前：水波纹被裁成圆形，不会出现方形半透明块
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE)
                .clip(AppShape.full)
                .clickable(onClick = onClick)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.displayName.take(1),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.avatarUrl != null) {
                AsyncImage(
                    model = state.avatarUrl,
                    contentDescription = state.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(AVATAR_SIZE)
                        .clip(AppShape.full),
                )
            }
        }
        if (state.isUploading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(IconSize.md),
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(Spacing.xl)
                    .clip(AppShape.full)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = PhosphorIcons.Regular.Camera,
                    contentDescription = "更换头像",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(IconSize.sm),
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = AppShape.extraLarge,
        color = starveilExtraColors.surfaceCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = action ?: value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (action != null) {
                MaterialTheme.colorScheme.primary
            } else {
                starveilExtraColors.textMuted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Spacing.md),
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = starveilExtraColors.divider,
        modifier = Modifier.padding(start = Spacing.lg),
    )
}

/** 相册里选出来的一张图：字节、文件名、MIME 由页面解析后交给 ViewModel。 */
internal class AvatarPick(val bytes: ByteArray, val fileName: String, val mime: String)

/** 图片缓存占用：Coil 的磁盘 + 内存缓存，读不到就当 0。 */
private fun imageCacheText(context: Context): String {
    val bytes = runCatching {
        val loader = SingletonImageLoader.get(context)
        (loader.diskCache?.size ?: 0L) + (loader.memoryCache?.size ?: 0L)
    }.getOrDefault(0L)
    return "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private fun clearImageCache(context: Context) {
    runCatching {
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }
}

private val AVATAR_SIZE = Spacing.xxl * 2
private const val PLACEHOLDER = "——"
