package com.starveil.feature.pay

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.PayTips

/**
 * 支付页（会员套餐 / 在线充值 / 卡密 / 单集购买）。
 *
 * 只渲染状态、上抛事件：下单、卡密、买集都在 [PayViewModel]。
 * **不在本模块打开浏览器**——H5 支付地址通过 [onOpenPayment] 交给 `:app`，
 * 直接 `startActivityForResult(BrowserActivity)`。
 *
 * 页面结构分两块，互不共用：
 * - 会员模式：[PayMembershipHero] → 待支付订单 → [PayPlanSection] → [PayPaymentSection]
 * → [PayCardRedeem] → 充值提示；
 * - 单集模式：[PayEpisodeBlock] → [PayPaymentSection]，不展示套餐与卡密。
 *
 * 底部提交按钮固定在滚动区之外（[PayBottomBar]），价格只出现在按钮文案里，不重复。
 */
@Composable
fun PayScreen(
    viewModel: PayViewModel,
    onBack: () -> Unit,
    onOpenPayment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PayEvent.OpenPayment -> onOpenPayment(event.url)
                is PayEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }
    PayContent(
        state = state,
        actions = PayActions(
            onBack = onBack,
            onSelectPlan = viewModel::onPlanSelected,
            onSelectPayment = viewModel::onPaymentSelected,
            onCardCodeChange = viewModel::onCardCodeChange,
            onRedeemCard = viewModel::redeemCard,
            onPay = viewModel::submit,
            onRetry = viewModel::retry,
            onOpenExternal = onOpenPayment,
        ),
        modifier = modifier,
    )
}

/** 页面回调集合，避免 [PayContent] 参数越堆越多（detekt 参数上限 5）。 */
private data class PayActions(
    val onBack: () -> Unit,
    val onSelectPlan: (Int) -> Unit,
    val onSelectPayment: (String) -> Unit,
    val onCardCodeChange: (String) -> Unit,
    val onRedeemCard: () -> Unit,
    val onPay: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenExternal: (String) -> Unit,
)

@Composable
private fun PayContent(state: PayUiState, actions: PayActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 外壳全局沉浸后不再替页面留状态栏位置：背景铺满，内容从状态栏下方开始。
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            PayTopBar(isEpisodeMode = state.isEpisodeMode, onBack = actions.onBack)
            when {
                state.isLoading -> StarveilLoadingScreen()
                state.errorMessage != null -> PayError(
                    message = state.errorMessage.orEmpty(),
                    onRetry = actions.onRetry,
                )
                state.isEmpty -> PayEmpty(onBack = actions.onBack)
                else -> PayBody(state = state, actions = actions)
            }
        }
    }
}

@Composable
private fun PayTopBar(isEpisodeMode: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = PhosphorIcons.Regular.ArrowLeft,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(IconSize.md).mirrorIconInRtl(),
            )
        }
        Text(
            text = if (isEpisodeMode) TITLE_EPISODE else TITLE_MEMBERSHIP,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PayBody(state: PayUiState, actions: PayActions) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(WEIGHT_FULL)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (state.isEpisodeMode) {
                EpisodeBody(state = state)
            } else {
                MembershipBody(state = state, actions = actions)
            }
        }
        PayBottomBar(state = state, onPay = actions.onPay)
    }
}

/** 会员模式：Hero → 订单提示 → 套餐 → 支付方式 → 卡密 → 充值提示。 */
@Composable
private fun MembershipBody(state: PayUiState, actions: PayActions) {
    PayMembershipHero(state = state)
    PayOrderNotice(state = state)
    PayPlanSection(state = state, onSelect = actions.onSelectPlan)
    PayPaymentSection(state = state, onSelect = actions.onSelectPayment)
    PayCardRedeem(
        state = state,
        onCodeChange = actions.onCardCodeChange,
        onRedeem = actions.onRedeemCard,
    )
    TipsBlock(tips = state.tips, onOpenExternal = actions.onOpenExternal)
}

/**
 * 单集模式：只回显影片/集数与积分规则。
 *
 * **不出现支付方式**：买断单集扣的是积分（服务端 `Payment::buyEpisode`），
 * 跟支付宝/微信没关系，摆一条渠道选择只会让人以为要付钱。
 */
@Composable
private fun EpisodeBody(state: PayUiState) {
    PayEpisodeBlock(state = state)
}

/**
 * 充值提示 + 在线充值入口。
 *
 * 一行：左边提示文案（单行省略），右边「在线充值」。整条给 chip 底和圆角，
 * 不再上下堆两行——原来文案一行、按钮一行，在支付页底部像两块散落的说明。
 * 两个都为空时整块不渲染。
 */
@Composable
private fun TipsBlock(tips: PayTips?, onOpenExternal: (String) -> Unit) {
    if (tips == null) return
    val hasHint = tips.payHint.isNotBlank()
    if (!hasHint && !tips.hasExternalRecharge) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.small)
            .background(starveilExtraColors.chipBackground)
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tips.payHint,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (tips.hasExternalRecharge) {
            TextButton(
                onClick = { onOpenExternal(tips.payUrl) },
                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.none),
                modifier = Modifier.height(Spacing.xxl),
            ) {
                Text(
                    text = EXTERNAL_RECHARGE,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 底部固定提交栏：一条分隔线 + 一个通栏主色按钮。
 *
 * 禁用态给 `chipBackground` 底 + 三级文字，和页面里可点的主色按钮明显区分；提交中在按钮内
 * 换成同尺寸指示器，高度不跳。价格只在 [PayUiState.submitLabel] 里出现一次。
 */
@Composable
internal fun PayBottomBar(
    state: PayUiState,
    onPay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Button(
            onClick = onPay,
            enabled = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.md)
                .height(ACTION_HEIGHT),
            shape = AppShape.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = starveilExtraColors.chipBackground,
                disabledContentColor = starveilExtraColors.textFaint,
            ),
        ) {
            when {
                state.isSubmitting -> CircularProgressIndicator(
                    modifier = Modifier.size(IconSize.item),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = Spacing.xxs,
                )

                else -> Text(text = state.submitLabel, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun PayError(message: String, onRetry: () -> Unit) {
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

/** 后端没给会员组配价时的空态（旧页会显示一堆 0 元套餐，这里不展示）。 */
@Composable
private fun PayEmpty(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.pageHorizontal),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "暂无可购买的套餐",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onBack, modifier = Modifier.padding(top = Spacing.md)) { Text("返回") }
    }
}

private const val TITLE_MEMBERSHIP = "会员中心"
private const val TITLE_EPISODE = "积分兑换"
private const val EXTERNAL_RECHARGE = "在线充值"
private const val WEIGHT_FULL = 1f

/** 提交按钮高度 48dp：拇指热区内，且比 Material 默认的 40dp 更"可点"。 */
private val ACTION_HEIGHT = Spacing.xxl + Spacing.lg

