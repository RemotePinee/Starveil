package com.starveil.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Check
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 登录/注册表单的"次要控件"与协议行。
 *
 * 从 `AuthComponents.kt` 拆出来：那个文件顶到了 400 行闸门（AGENTS.md 第 2 节），
 * 而本节与输入框实现没有共用代码，搬走后两边都更短。
 */

/**
 * 验证码按钮：常驻在输入框内部右侧（[AuthTextField] 的 trailing），
 * 倒计时期间不可点，文案由状态给出。不用独立外挂按钮——那会让输入框和按钮
 * 各自成为一个矩形，视觉上一行被切成两块。
 */
@Composable
internal fun AuthCodeButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary else starveilExtraColors.textFaint,
        )
    }
}

/** 字段级错误：贴在输入框下方的小红字。 */
@Composable
internal fun AuthFieldError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

/** 表单级错误（后端拒绝 / 网络异常）：放在主按钮上方，不整页替换。 */
@Composable
internal fun AuthFormError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 协议行的三个回调，收成一个参数，避免 [AuthAgreementRow] 参数超限。 */
internal data class AuthAgreementActions(
    val onCheckedChange: (Boolean) -> Unit,
    val onOpenTerms: () -> Unit,
    val onOpenPrivacy: () -> Unit,
)

/**
 * 服务协议行：自绘勾选框 + 可点的《用户协议》《隐私政策》。
 *
 * 未勾选不在按钮上禁用——历史版本是点了才提示"若不认同本服务协议将无法使用本软件"，
 * 禁用按钮会让用户不知道原因。
 *
 * 勾选框自己画（20dp 圆角方块），不用 M3 Checkbox：那个控件强制 48dp 触摸区，
 * 左右各 12dp 内边距把文案顶离输入框左边缘，整行看起来比上方表单缩进一格。
 * 触摸区交给整行 clickable，点文字也能勾上。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AuthAgreementRow(
    checked: Boolean,
    error: String?,
    actions: AuthAgreementActions,
    /** 锁屏这类居中版式传 true：勾选框 + 文案整体居中，不再贴左。 */
    centered: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppShape.small)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { actions.onCheckedChange(!checked) },
                )
                .padding(vertical = Spacing.xs),
            horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgreementCheckMark(checked = checked, invalid = error != null)
            Spacer(modifier = Modifier.width(Spacing.sm))
            FlowRow(
                modifier = if (centered) Modifier else Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                AgreementLabel(text = "我已阅读并同意")
                AgreementLink(text = "《用户协议》", onClick = actions.onOpenTerms)
                AgreementLabel(text = "和")
                AgreementLink(text = "《隐私政策》", onClick = actions.onOpenPrivacy)
            }
        }
        AgreementErrorSlot(message = error, centered = centered)
    }
}

/** 勾选框：20dp 圆角方块，选中填主色并压一个勾；未选中只剩一圈细边。 */
@Composable
private fun AgreementCheckMark(checked: Boolean, invalid: Boolean) {
    val fill = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent
    val edge = when {
        invalid -> MaterialTheme.colorScheme.error
        checked -> MaterialTheme.colorScheme.primary
        else -> starveilExtraColors.divider
    }
    Box(
        modifier = Modifier
            .size(IconSize.item)
            .clip(AppShape.extraSmall)
            .background(fill)
            .border(Spacing.hairline, edge, AppShape.extraSmall),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = PhosphorIcons.Fill.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(IconSize.sm),
            )
        }
    }
}

/**
 * 协议文字：与勾选框居中对齐。
 *
 * 刻意不加 top padding：原来"我已阅读并同意"留 4dp、"《用户协议》"留 2dp，
 * 两条基线差 2dp，看起来就是"字没对齐"。触摸区靠整行高度，不需要额外内边距。
 */
@Composable
private fun AgreementLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = starveilExtraColors.textFaint,
    )
}

@Composable
private fun AgreementLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * 协议行下方的错误位：**始终占一行高度**。
 *
 * 之前只有出错才插入这行红字，一点"登录"整页就往上顶一行、按钮跟着跑位，
 * 看着像界面在抖。固定占位之后高度不变，红字只在原地出现。
 * 文案过长允许撑高——宁可多一行，也不截断提示。
 */
@Composable
private fun AgreementErrorSlot(message: String?, centered: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.xl)
            .padding(top = Spacing.xs),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart,
    ) {
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
