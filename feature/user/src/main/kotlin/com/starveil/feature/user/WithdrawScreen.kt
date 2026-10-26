package com.starveil.feature.user

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Check
import com.adamglin.phosphoricons.regular.ChatCircle
import com.adamglin.phosphoricons.regular.Coin
import com.adamglin.phosphoricons.regular.Wallet
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.WithdrawChannel

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.starveil.core.designsystem.token.IconSize

/**
 * 金币提现页。
 * 本模块不持有 NavController（AGENTS.md 第 8 节）。
 */
@Composable
fun WithdrawScreen(
    viewModel: WithdrawViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is WithdrawEvent.Message ->
                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }
    WithdrawContent(
        state = state,
        actions = WithdrawActions(
            onBack = onBack,
            onAmountChange = viewModel::onAmountChange,
            onAccountChange = viewModel::onAccountChange,
            onNameChange = viewModel::onNameChange,
            onChannelSelected = viewModel::onChannelSelected,
            onSubmit = viewModel::submit,
            onRetry = viewModel::retry,
            onQrcodePicked = viewModel::onQrcodePicked,
            onQrcodeRemove = viewModel::onQrcodeRemoved,
            onQrcodePickFailed = viewModel::onQrcodePickFailed,
            onPendingConfirmed = viewModel::onPendingConfirmed,
        ),
        modifier = modifier,
    )
}

/** 回调集合，避免 [WithdrawContent] 参数越堆越多（detekt 参数上限 5）。 */
private data class WithdrawActions(
    val onBack: () -> Unit,
    val onAmountChange: (String) -> Unit,
    val onAccountChange: (String) -> Unit,
    val onNameChange: (String) -> Unit,
    val onChannelSelected: (WithdrawChannel) -> Unit,
    val onSubmit: () -> Unit,
    val onRetry: () -> Unit,
    val onQrcodePicked: (QrcodePick) -> Unit,
    val onQrcodeRemove: () -> Unit,
    val onQrcodePickFailed: () -> Unit,
    val onPendingConfirmed: () -> Unit,
)

@Composable
private fun WithdrawContent(
    state: WithdrawUiState,
    actions: WithdrawActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            WithdrawTopBar(onBack = actions.onBack)
            HorizontalDivider(color = starveilExtraColors.divider)
            when {
                state.submitted -> SubmittedBlock(onBack = actions.onBack)
                state.isLoading -> LoadingBox()
                state.loadError != null -> ErrorBlock(
                    message = state.loadError.orEmpty(),
                    onRetry = actions.onRetry,
                )

                else -> WithdrawForm(state = state, actions = actions)
            }
        }
    }
}

@Composable
private fun WithdrawForm(state: WithdrawUiState, actions: WithdrawActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TipsCard(state = state)
        AmountField(state = state, onChange = actions.onAmountChange)
        ChannelSection(selected = state.channel, onSelect = actions.onChannelSelected)
        PayeeFields(state = state, actions = actions)
        WithdrawQrcodeSection(
            qrcodePath = state.qrcodePath,
            previewUri = state.qrcodePreview,
            uploading = state.uploadingQrcode,
            actions = QrcodeActions(
                onPicked = actions.onQrcodePicked,
                onRemove = actions.onQrcodeRemove,
                onPickFailed = actions.onQrcodePickFailed,
            ),
        )
        state.pendingConfirm?.let { pending ->
            WithdrawPendingNotice(pending = pending, onRetry = actions.onPendingConfirmed)
        }
        SubmitButton(state = state, onSubmit = actions.onSubmit)
    }
}

/** 余额 + 最低额 + 比例说明；余额是主角，其余两项合并成一行说明。 */
@Composable
private fun TipsCard(state: WithdrawUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.medium)
            .background(starveilExtraColors.surfaceElevated)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = PhosphorIcons.Regular.Coin,
            contentDescription = null,
            tint = starveilExtraColors.brandGold,
            modifier = Modifier.size(IconSize.lg),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                text = state.balanceText,
                style = MaterialTheme.typography.titleMedium,
                color = starveilExtraColors.brandGold,
            )
            // 「最低 20 元 · 10 金币=1 元」一行说完，比原来两行小字更省视线
            val minLabel = if (state.minAmountYuan > 0) "最低 ${state.minAmountYuan} 元" else ""
            val ratio = state.tips?.ratioText.orEmpty()
            val note = listOf(minLabel, ratio).filter { it.isNotBlank() }.joinToString(" · ")
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = starveilExtraColors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun AmountField(state: WithdrawUiState, onChange: (String) -> Unit) {
    WithdrawField(
        value = state.amount,
        onChange = onChange,
        label = "提现金额（元）",
        error = state.amountError,
        keyboardType = KeyboardType.Decimal,
    )
}

/** 收款渠道：平铺成两张等宽卡片，比纵向单选省高度，也更好点。 */
@Composable
private fun ChannelSection(selected: WithdrawChannel, onSelect: (WithdrawChannel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = "收款方式",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            WithdrawChannel.entries.forEach { channel ->
                ChannelCard(
                    channel = channel,
                    selected = channel == selected,
                    onClick = { onSelect(channel) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: WithdrawChannel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(AppShape.medium)
            .background(
                if (selected) accent.copy(alpha = CHANNEL_SELECTED_ALPHA) else starveilExtraColors.surfaceCard,
            )
            .border(
                width = Spacing.hairline,
                color = if (selected) accent else starveilExtraColors.divider,
                shape = AppShape.medium,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = if (channel == WithdrawChannel.ALIPAY) {
                PhosphorIcons.Regular.Wallet
            } else {
                PhosphorIcons.Regular.ChatCircle
            },
            contentDescription = null,
            tint = if (selected) accent else starveilExtraColors.textMuted,
            modifier = Modifier.size(IconSize.item),
        )
        Text(
            text = channel.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = PhosphorIcons.Fill.Check,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(IconSize.sm),
            )
        }
    }
}

@Composable
private fun PayeeFields(state: WithdrawUiState, actions: WithdrawActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        // 原来两个框直接贴在一起，没有间距，看起来像一个双行框
        WithdrawField(
            value = state.account,
            onChange = actions.onAccountChange,
            label = "收款账号",
        )
        WithdrawField(
            value = state.name,
            onChange = actions.onNameChange,
            label = "收款姓名",
        )
    }
}

@Composable
private fun SubmitButton(state: WithdrawUiState, onSubmit: () -> Unit) {
    Button(
        onClick = onSubmit,
        enabled = state.canSubmit,
        shape = AppShape.medium,
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.rowHeight),
    ) {
        Text(
            text = state.submitLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 成功态：接口只说"已提交"，审核结果不在响应里，所以这里不再假装成功提现。 */
@Composable
private fun SubmittedBlock(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.pageHorizontal),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "已提交，等待审核",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "到账时间与审核结果以平台通知为准",
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        TextButton(onClick = onBack, modifier = Modifier.padding(top = Spacing.md)) { Text("返回") }
    }
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize()) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.pageHorizontal),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = Spacing.md)) { Text("重试") }
    }
}

/**
 * 表单输入统一走这里。
 *
 * 用填充底 + 无下划线的 TextField，而不是 OutlinedTextField：暗色主题下 outline 只有
 * 一档很暗的灰，整个框看起来是空的；浅色主题下又容易糊成一片。
 */
@Composable
private fun WithdrawField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        TextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = AppShape.medium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = starveilExtraColors.surfaceCard,
                unfocusedContainerColor = starveilExtraColors.surfaceCard,
                errorContainerColor = starveilExtraColors.surfaceCard,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = starveilExtraColors.textMuted,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            FieldError(message = error)
        }
    }
}
@Composable
private fun FieldError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** 选中渠道的底色透明度：不做成实心主色块，避免和底部主按钮抢视线。 */
private const val CHANNEL_SELECTED_ALPHA = 0.14f
