package com.starveil.feature.user

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Megaphone
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.AppMessage
import com.starveil.core.model.MessageKind

/**
 * 消息中心列表：公告与推送混排，靠左侧图标和标签区分。
 *
 * 不做分页 UI：消息是低频内容，一页 20 条足够，滚到底再拉下一页反而要维护一套
 * 加载更多状态；真到需要分页的量级再加。
 */
@Composable
fun MessageListScreen(
    state: MessageListUiState,
    onBack: () -> Unit,
    onOpen: (AppMessage) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        MessageTopBar(onBack = onBack)
        // 状态区统一吃 weight(1f)：在 Column 里直接用 fillMaxSize，内容会按整屏高度居中，
        // 比"标题栏下面的剩余区域"高出一截——加载态切空态时那一下位移就是它。
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            MessageBody(state = state, onOpen = onOpen, onRetry = onRetry)
        }
    }
}

/** 消息区三态：加载 / 失败 / 空列表 / 有内容。抽出来是为了压住外层函数的复杂度。 */
@Composable
private fun MessageBody(
    state: MessageListUiState,
    onOpen: (AppMessage) -> Unit,
    onRetry: () -> Unit,
) {
    // 状态之间交叉淡入：加载环与空态文案是同一帧替换的，硬切看起来就是"跳一下"。
    Crossfade(targetState = state.phase(), label = "message-phase") { phase ->
        when (phase) {
            MessagePhase.Loading -> StarveilLoadingScreen()
            MessagePhase.Error -> MessageError(onRetry = onRetry)
            MessagePhase.Empty -> MessageEmpty()
            MessagePhase.List -> MessageList(items = state.items, onOpen = onOpen)
        }
    }
}

/** 页面当前处于哪一态：只给 Crossfade 当 key，避免整份 state 变化就重放动画。 */
private enum class MessagePhase { Loading, Error, Empty, List }

private fun MessageListUiState.phase(): MessagePhase = when {
    isLoading && items.isEmpty() -> MessagePhase.Loading
    errorMessage != null && items.isEmpty() -> MessagePhase.Error
    items.isEmpty() -> MessagePhase.Empty
    else -> MessagePhase.List
}

@Composable
private fun MessageList(items: List<AppMessage>, onOpen: (AppMessage) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = Spacing.pageHorizontal,
            end = Spacing.pageHorizontal,
            top = Spacing.sm,
            bottom = Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(items, key = { it.id }) { message ->
            MessageRow(message = message, onClick = { onOpen(message) })
        }
    }
}

@Composable
private fun MessageTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.rowHeight)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = PhosphorIcons.Regular.CaretLeft,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(IconSize.md),
            )
        }
        Text(
            text = "消息",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 一条消息：左图标 + 标题 + 摘要 + 时间，未读时标题加粗并带一个点。
 *
 * 公告用中性色 `Info`，推送用品牌色 `Megaphone`——两种语义在一屏里要能一眼分开，
 * 但只靠图标和颜色，不加彩色标签块。
 */
@Composable
private fun MessageRow(message: AppMessage, onClick: () -> Unit) {
    val isPush = message.kind == MessageKind.PUSH
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.medium)
            .background(starveilExtraColors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (isPush) {
                PhosphorIcons.Regular.Megaphone
            } else {
                PhosphorIcons.Regular.Info
            },
            contentDescription = null,
            tint = if (isPush) {
                MaterialTheme.colorScheme.primary
            } else {
                starveilExtraColors.textMuted
            },
            modifier = Modifier.size(IconSize.item),
        )
        Spacer(Modifier.size(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!message.read) {
                    Spacer(Modifier.size(Spacing.xs))
                    Box(
                        modifier = Modifier
                            .size(UNREAD_DOT)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = ROW_LINE_HEIGHT),
                color = starveilExtraColors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MessageEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "还没有消息",
            style = MaterialTheme.typography.bodyMedium,
            color = starveilExtraColors.textMuted,
        )
    }
}

@Composable
private fun MessageError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "消息加载失败",
            style = MaterialTheme.typography.bodyMedium,
            color = starveilExtraColors.textMuted,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "重试",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(AppShape.small)
                .clickable(onClick = onRetry)
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        )
    }
}

/** 未读小红点：比铃铛上那颗略大，列表里要能看清。 */
private val UNREAD_DOT = Spacing.xs + Spacing.xxs

/** 摘要行高：11.5sp 正文压 17sp 行高，两行摘要不挤。 */
private val ROW_LINE_HEIGHT = 17.sp
