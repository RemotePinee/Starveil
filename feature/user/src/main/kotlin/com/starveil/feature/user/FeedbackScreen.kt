package com.starveil.feature.user

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.starveil.core.designsystem.mirrorIconInRtl
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing

/**
 * 反馈：一个输入框 + 提交按钮，走 `v1.gbook` 留言板。
 *
 * 不展示历史留言：那是公开留言板，跟"给开发者提意见"不是一回事，混在一起反而看不清。
 */
@Composable
fun FeedbackScreen(
    viewModel: FeedbackViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = PhosphorIcons.Regular.ArrowLeft,
                        contentDescription = "返回",
                        tint = starveilExtraColors.textMuted,
                        modifier = Modifier.size(IconSize.md).mirrorIconInRtl(),
                    )
                }
                Text(
                    text = TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Surface(
                    shape = AppShape.medium,
                    color = starveilExtraColors.surfaceCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(INPUT_HEIGHT),
                ) {
                    BasicTextField(
                        value = state.content,
                        onValueChange = viewModel::onContentChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.md),
                        decorationBox = { inner ->
                            Box {
                                if (state.content.isEmpty()) {
                                    Text(
                                        text = HINT,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = starveilExtraColors.textFaint,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
                Text(
                    text = "${state.content.length} / $MAX_LENGTH",
                    style = MaterialTheme.typography.labelSmall,
                    color = starveilExtraColors.textFaint,
                    modifier = Modifier.align(Alignment.End),
                )
                TextButton(
                    onClick = viewModel::submit,
                    enabled = state.canSubmit,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(IconSize.item),
                            color = starveilExtraColors.textFaint,
                            strokeWidth = Spacing.xxs,
                        )
                    } else {
                        Text(text = ACTION_SEND)
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

private const val TITLE = "反馈"
private const val HINT = "说说哪里不好用，或想要什么功能"
private const val ACTION_SEND = "提交"
private const val MAX_LENGTH = 300
private val INPUT_HEIGHT = Spacing.xxl * 5
