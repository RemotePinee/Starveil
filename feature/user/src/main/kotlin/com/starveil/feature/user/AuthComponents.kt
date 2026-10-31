package com.starveil.feature.user

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.Eye
import com.adamglin.phosphoricons.regular.EyeSlash
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 登录 / 注册 / 找回密码三页共用的版式与控件。
 *
 * 三页必须长得一样，所以输入框、主按钮、验证码行、协议行都收在这里，页面只负责排列字段。
 * 颜色只取 `MaterialTheme.colorScheme` 与 [starveilExtraColors]；间距与控件高度都取令牌。
 */

/** 聚焦描边的不透明度：再淡看不出框是活的，再浓就盖过输入内容。 */
private const val FOCUS_OUTLINE_ALPHA = 0.45f

/**
 * 输入框底色：半透明白，不用 surfaceCard / chipBackground。
 *
 * 这两个令牌是"给纯色底叠一档"的暗色台阶，而登录页背景是 surface + 16% 品牌色渐变，
 * 台阶叠在同亮度上等于没叠——实测框内和框外都是 (54,40,49)，聚焦后只是描边多了圈粉。
 * 半透明白压在任何底色上都能往亮里走，换皮肤也不会跟背景融成一整块。
 */
private const val FIELD_ALPHA = 0.05f
private const val FIELD_FOCUSED_ALPHA = 0.10f

/**
 * 三页共用的骨架：左上返回、右上文字动作、品牌标记、标题与一句副标题，下面接表单。
 *
 * 背景用 [fogBackdrop] 而不是纯色：注册/找回是整页跳转，纯黑会让它看起来像
 * "另一个 App 的设置页"；和「我的」同一层彩雾才像同一个产品。
 */
@Composable
internal fun AuthScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .authBackdrop()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = PhosphorIcons.Regular.ArrowLeft,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(IconSize.md).mirrorIconInRtl(),
                )
            }
            if (actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = starveilExtraColors.textMuted,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.xl))
        BrandMark()
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
        )
        Spacer(modifier = Modifier.height(Spacing.xl))
        content()
        Spacer(modifier = Modifier.height(Spacing.xxl))
    }
}

/**
 * 56dp、圆角 16dp、实底无描边的输入框。
 *
 * 不用 `OutlinedTextField`：Material3 的最小高度与浮动标签会撑成另一种长相，
 * 要"占位符在框内 + 实底填充"，直接用 [BasicTextField] 自绘。
 *
 * **聚焦**：底色再亮一档并压一圈主色细边（45%），光标本身也是主色。
 * 不用中性色描边——深色底上 1dp divider 线只有 8% 对比度，勾不出形状反而显脏；
 * 主色细边才读得出"这个框现在是活的"。光标本身也是主色。
 * 错误态靠下方红字，不给整个框染色——红底会把输入内容吃掉。
 *
 * @param trailing 右侧附加内容（验证码按钮等）。传了就与输入区之间加一条 1dp 竖线，
 *   不再出现"按钮浮在框外"的错位。
 */
@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisibility: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusOutline = MaterialTheme.colorScheme.primary.copy(alpha = FOCUS_OUTLINE_ALPHA)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.control)
                .clip(AppShape.extraLarge)
                .background(
                    starveilExtraColors.onImage.copy(
                        alpha = if (focused) FIELD_FOCUSED_ALPHA else FIELD_ALPHA,
                    ),
                )
                .border(
                    width = Spacing.hairline,
                    color = if (focused) focusOutline else Color.Transparent,
                    shape = AppShape.extraLarge,
                )
                .padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = fieldTransformation(isPassword, passwordVisible),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = interactionSource,
                decorationBox = { innerTextField ->
                    FieldDecoration(
                        value = value,
                        placeholder = placeholder,
                        innerTextField = innerTextField,
                    )
                },
            )
            if (isPassword && onTogglePasswordVisibility != null) {
                PasswordToggle(visible = passwordVisible, onToggle = onTogglePasswordVisibility)
            }
            if (trailing != null) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = Spacing.md)
                        .width(Spacing.hairline)
                        .height(Spacing.xl)
                        .background(starveilExtraColors.divider),
                )
                trailing()
            }
        }
        if (error != null) {
            AuthFieldError(message = error)
        }
    }
}
/** 主按钮：主色、56dp、全宽、16dp 圆角；提交中只在按钮内显示进度。 */
@Composable
internal fun AuthPrimaryButton(
    text: String,
    isSubmitting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 形状默认 16dp 圆角；强制登录那种"整页只有一个操作"的场景传 [AppShape.full] 做胶囊。 */
    shape: Shape = AppShape.extraLarge,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isSubmitting,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
        modifier = modifier
            .fillMaxWidth()
            .height(Spacing.control),
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = Spacing.xxs,
                modifier = Modifier.size(IconSize.item),
            )
        } else {
            Text(text = text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 首屏唯一操作按钮：白底黑字胶囊。
 *
 * 深色底上品牌色填充会显得刺眼（尤其大面积），而"近白底 + 深色字"是最稳的高对比组合：
 * 一眼能找到、不抢品牌色的使用位置，也不会在暗背景上发飘。
 */
@Composable
internal fun AuthHeroButton(
    text: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = AppShape.extraLarge,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            disabledContentColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ),
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
        modifier = modifier
            .fillMaxWidth()
            .height(Spacing.control),
    ) {
        Text(
            text = if (busy) "登录中…" else text,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * 次要按钮：与主按钮同尺寸同圆角，靠"亮一档的底 + 主色文字"降级。
 *
 * 用于设备一键登录、暂不登录这类"和主操作并列但不该抢焦点"的动作。
 */
@Composable
internal fun AuthSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = AppShape.extraLarge,
        colors = ButtonDefaults.buttonColors(
            containerColor = starveilExtraColors.surfaceElevated,
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = starveilExtraColors.surfaceElevated,
            disabledContentColor = starveilExtraColors.textFaint,
        ),
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
        modifier = modifier
            .fillMaxWidth()
            .height(Spacing.control),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}


/** 密码框按可见性切换掩码。 */
private fun fieldTransformation(isPassword: Boolean, visible: Boolean): VisualTransformation =
    if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None

/** 空值时的占位符 + 真实输入层。 */
@Composable
private fun FieldDecoration(
    value: String,
    placeholder: String,
    innerTextField: @Composable () -> Unit,
) {
    Box(contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.titleMedium,
                color = starveilExtraColors.textFaint,
            )
        }
        innerTextField()
    }
}

/** 密码可见性切换按钮。 */
@Composable
private fun PasswordToggle(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (visible) PhosphorIcons.Regular.Eye else PhosphorIcons.Regular.EyeSlash,
            contentDescription = if (visible) "隐藏密码" else "显示密码",
            tint = starveilExtraColors.textFaint,
            modifier = Modifier.size(IconSize.sm),
        )
    }
}

