package com.starveil.feature.pay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.Spacing

/**
 * 卡密兑换：独立卡片，输入框 + 兑换按钮。
 *
 * 与套餐、支付方式平级：没有在线支付渠道的会员也走这条路（旧 、
 * `:247-267`）。**到账提示不在这里算**——会员卡给天数、金币卡给金币数由
 * `PayViewModel.redeemCard` 按 `CardRedeem.cardType` 一次性事件发出，页面只负责渲染。
 *
 * 按钮用 [PayUiState.canRedeem]（非空且非提交中）置灰，避免空输入白白打一次接口。
 */
@Composable
internal fun PayCardRedeem(
    state: PayUiState,
    onCodeChange: (String) -> Unit,
    onRedeem: () -> Unit,
) {
    PaySectionCard(title = SECTION_TITLE, hint = HINT) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CardCodeField(
                code = state.cardCode,
                enabled = !state.isSubmitting,
                onCodeChange = onCodeChange,
                modifier = Modifier.weight(WEIGHT_FULL),
            )
            Button(
                onClick = onRedeem,
                enabled = state.canRedeem,
                shape = AppShape.medium,
                modifier = Modifier.height(FIELD_HEIGHT),
                contentPadding = PaddingValues(horizontal = Spacing.lg),
            ) {
                Text(
                    text = ACTION,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = CAPTION,
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textFaint,
            modifier = Modifier.padding(top = Spacing.xxs),
        )
    }
}

/**
 * 卡密输入框：40dp 胶囊，与兑换键同高同圆角（AppShape.medium）。
 *
 * 不用 `OutlinedTextField`：它自带 56dp 最小高度和一圈描边，放进卡片里又高又重，
 * 和右边那颗按钮也拼不齐。这里自绘底色 + 无边框，视觉上是一行东西。
 */
@Composable
private fun CardCodeField(
    code: String,
    enabled: Boolean,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(FIELD_HEIGHT)
            .clip(AppShape.medium)
            .background(starveilExtraColors.chipBackground)
            .padding(horizontal = Spacing.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            // 卡密是大写十六进制，键盘直接给大写，少一次"输了小写要重输"的来回
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (code.isEmpty()) {
                        Text(
                            text = PLACEHOLDER,
                            style = MaterialTheme.typography.bodySmall,
                            color = starveilExtraColors.textFaint,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

private const val SECTION_TITLE = "卡密兑换"
private const val HINT = "会员卡 / 金币卡"
private const val PLACEHOLDER = "请输入卡密"
private const val CAPTION = "兑换成功后按卡密类型到账：会员卡延长期限，金币卡充入金币"
private const val ACTION = "兑换"
private const val WEIGHT_FULL = 1f
private val FIELD_HEIGHT = Spacing.xxl + Spacing.sm
