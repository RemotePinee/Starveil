package com.starveil.feature.user

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账号与设置。
 *
 * 只放服务端真支持的项：昵称（`v1.user/index`）、头像（`v1.upload/user`）、
 * 只读的账号 / 会员信息，以及本地能做的图片缓存清理与版本号。
 * 退出登录在「我的」页已有，这里不再重复。
 */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
    onLoginClick: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ProfileEvent.SignedOut -> onSignedOut()
                is ProfileEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }
    var editingNick by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let { picked ->
            // 读流是磁盘 IO：相册回调在主线程，先切到 IO，再回主线程交给 ViewModel
            scope.launch {
                withContext(Dispatchers.IO) { readAvatar(context, picked) }?.let { pick ->
                    viewModel.uploadAvatar(pick.bytes, pick.fileName, pick.mime)
                }
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            ProfileTopBar(onBack = onBack)
            ProfileBody(
                state = state,
                actions = ProfileActions(
                    onLoginClick = onLoginClick,
                    onEditNick = { editingNick = true },
                    onPickAvatar = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                ),
                onRetry = viewModel::retry,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    if (editingNick) {
        NickNameDialog(
            initial = state.nickName,
            saving = state.isSaving,
            onDismiss = { editingNick = false },
            onConfirm = { name ->
                editingNick = false
                viewModel.saveNickName(name)
            },
        )
    }
}

private class ProfileActions(
    val onLoginClick: () -> Unit,
    val onEditNick: () -> Unit,
    val onPickAvatar: () -> Unit,
)

@Composable
private fun ProfileBody(state: ProfileUiState, actions: ProfileActions, onRetry: () -> Unit) {
    when {
        state.needsLogin -> PagePlaceholder(
            title = "登录后查看资料",
            detail = "登录后可修改昵称、头像并查看会员状态",
            actionLabel = "去登录",
            onAction = actions.onLoginClick,
        )

        state.isLoading -> StarveilLoadingScreen()

        state.errorMessage != null -> PagePlaceholder(
            title = "资料加载失败",
            detail = state.errorMessage.orEmpty(),
            actionLabel = "重试",
            onAction = onRetry,
        )

        else -> {
            ProfileAccountCard(
                state = state,
                onEditNick = actions.onEditNick,
                onPickAvatar = actions.onPickAvatar,
            )
            ProfileInfoCard(state = state)
            ProfileAppCard(versionName = state.versionName)
        }
    }
}

@Composable
private fun ProfileTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = PhosphorIcons.Regular.ArrowLeft,
            contentDescription = "返回",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(IconSize.md)
                .clickable(onClick = onBack)
                // 只翻图标本身：点击区是同一个 Icon，不要连坐标一起镜像
                .mirrorIconInRtl(),
        )
        Text(
            text = "账号与设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NickNameDialog(
    initial: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改昵称") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(NICK_MAX) },
                singleLine = true,
                label = { Text("昵称") },
                shape = RoundedCornerShape(Spacing.md),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = !saving) {
                Text(if (saving) "保存中" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 读相册里选中的图；服务端上限 2MB、只收 JPG/PNG/WEBP。必须在 IO 线程调用。 */
private fun readAvatar(context: Context, uri: Uri): AvatarPick? = runCatching {
    val bytes = readPickedBytes(context, uri, AVATAR_MAX_BYTES) ?: return null
    val type = context.contentResolver.getType(uri).orEmpty()
    val mime = type.takeIf { it.startsWith("image/") } ?: AVATAR_FALLBACK_MIME
    val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: AVATAR_FALLBACK_NAME
    AvatarPick(bytes = bytes, fileName = name, mime = mime)
}.getOrNull()

private const val NICK_MAX = 20
private const val AVATAR_MAX_BYTES = 2 * 1024 * 1024
private const val AVATAR_FALLBACK_MIME = "image/jpeg"
private const val AVATAR_FALLBACK_NAME = "avatar.jpg"
