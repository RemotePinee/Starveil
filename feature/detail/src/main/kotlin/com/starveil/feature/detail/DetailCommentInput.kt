package com.starveil.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.designsystem.interaction.rememberHaptics
import com.starveil.core.designsystem.interaction.HapticKind

/**
 * 输入框 + 发送。
 *
 * 详情页与竖屏短剧的评论抽屉共用这一份输入（跨模块只共享窄接口）：
 * 宿主自己决定它钉在底部还是跟在列表后面。
 *
 * 草稿用 `rememberSaveable` 跨旋屏保留；发布成功后由 [CommentSectionState.postedCount]
 * 触发清空，失败时留着草稿，用户不用重打（后端 30 秒冷却已经很折腾人）。
 * 换账号时 ViewModel 会换一份状态、`postedCount` 回到 0，因此旧账号的回包不会清掉新草稿。
 *
 * 不用 `OutlinedTextField`：它自带 56dp 最小高度与 supportingText 预留位，
 * 和发送键拼在一起必然一大一小。这里自绘 44dp 胶囊，输入框与按钮同高同圆角。
 */
@Composable
fun CommentInput(
    posting: Boolean,
    postedCount: Int,
    onSubmit: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val haptics = rememberHaptics()
    LaunchedEffect(postedCount) {
        if (postedCount > 0) {
            // 发出去那一刻给一次确认触感，用户不用盯着列表确认
            haptics(HapticKind.Confirm)
            draft = ""
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommentField(
                draft = draft,
                enabled = !posting,
                onDraftChange = { draft = it.take(COMMENT_MAX_LENGTH) },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onSubmit(draft) },
                enabled = !posting && draft.isNotBlank(),
                shape = AppShape.large,
                contentPadding = PaddingValues(horizontal = Spacing.lg),
                modifier = Modifier.height(CommentFieldHeight),
            ) {
                if (posting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(IconSize.sm),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(text = "发送", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        // 字数只在有草稿时出现，空态不留一行灰字
        if (draft.isNotEmpty()) {
            Text(
                text = "${draft.length}/$COMMENT_MAX_LENGTH",
                style = MaterialTheme.typography.labelSmall,
                color = starveilExtraColors.textFaint,
                modifier = Modifier.padding(start = Spacing.md, top = Spacing.xxs),
            )
        }
    }
}

/** 输入框本体：44dp 胶囊，圆角与发送键一致（AppShape.large）。 */
@Composable
private fun CommentField(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(CommentFieldHeight)
            .clip(AppShape.large)
            .background(starveilExtraColors.surfaceCard)
            .padding(horizontal = Spacing.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) {
                        FieldHint()
                    }
                    inner()
                }
            },
        )
    }
}

/** 输入提示：只在草稿为空时显示。 */
@Composable
private fun FieldHint() {
    Text(
        text = "说点什么…",
        style = MaterialTheme.typography.bodyMedium,
        color = starveilExtraColors.textFaint,
    )
}

/** 输入框与发送键共用的高度 44dp：比 Material 默认的 56dp 矮一档，行内更轻。 */
private val CommentFieldHeight: Dp = Spacing.xxl + Spacing.md
