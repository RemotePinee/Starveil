package com.starveil.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.starveil.core.designsystem.component.StarveilLoading
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Comment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.PaddingValues

/**
 * 评论列表区：标题 + 前 [COMMENT_PREVIEW_LIMIT] 条 + 「查看全部 N 条」/「加载更多」，不含输入框。
 *
 * 详情页与竖屏短剧的评论抽屉共用这一份渲染，两者只通过 [CommentActions] 接动作；
 * 输入框单独由 [CommentInput] 组合，宿主要把输入钉在底部时就不必让列表跟着滚。
 *
 * 旧页把「更多评论」做成跳评论列表 Activity，
 * 详情页只留一个入口；这里同页展开——跳走会丢掉当前播放源与选集位置。
 * 评论区失败只影响这一块，不把宿主页面打成错误态。
 */
@Composable
fun CommentList(
    state: CommentSectionState,
    actions: CommentActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        DetailCommentHeader(total = state.total)
        DetailCommentBody(state = state, actions = actions)
    }
}

/**
 * 详情页的评论区：列表 + 输入框同页展开。
 *
 * 输入框与发送按钮都禁用 `posting`，避免连点撞上后端 30 秒冷却。
 */
@Composable
internal fun DetailCommentSection(
    state: CommentSectionState,
    actions: DetailActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
    ) {
        CommentList(state = state, actions = actions.commentActions())
        CommentInput(
            posting = state.posting,
            postedCount = state.postedCount,
            onSubmit = actions.onSubmitComment,
        )
    }
}

private fun DetailActions.commentActions(): CommentActions = CommentActions(
    onExpand = onExpandComments,
    onLoadMore = onLoadMoreComments,
    onRetry = onRetryComments,
    onSubmit = onSubmitComment,
)

/** 四态：首屏加载 / 首屏失败 / 空 / 有内容。追加页的失败只弹提示，不进这里。 */
@Composable
private fun DetailCommentBody(state: CommentSectionState, actions: CommentActions) {
    when {
        state.isLoading && state.items.isEmpty() -> DetailCommentsLoading()

        state.error != null && state.items.isEmpty() -> DetailCommentError(
            message = state.error,
            onRetry = actions.onRetry,
        )

        state.items.isEmpty() -> DetailCommentEmpty()

        else -> DetailCommentList(state = state, actions = actions)
    }
}

@Composable
private fun DetailCommentList(state: CommentSectionState, actions: CommentActions) {
    state.visibleItems.forEach { comment -> DetailCommentRow(comment) }
    if (state.isExpanded && state.hasMore) {
        DetailCommentMore(isLoading = state.isLoadingMore, onLoadMore = actions.onLoadMore)
    }
    if (!state.isExpanded && state.hiddenCount > 0) {
        TextButton(
            onClick = actions.onExpand,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.pageHorizontal),
        ) {
            Text(text = "查看全部 ${state.total} 条", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DetailCommentHeader(total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = "评论",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (total > 0) {
            Text(
                text = "$total 条",
                style = MaterialTheme.typography.bodySmall,
                color = starveilExtraColors.textMuted,
            )
        }
    }
}

/** 单条评论：头像 + 昵称 + 时间 + 正文。正文不截断。 */
@Composable
private fun DetailCommentRow(comment: Comment) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        DetailCommentAvatar(comment = comment)
        Column(modifier = Modifier.weight(1f)) {
            CommentAuthorLine(comment = comment)
            CommentContent(comment = comment)
        }
    }
}

/** 昵称 + 时间一行。 */
@Composable
private fun CommentAuthorLine(comment: Comment) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = comment.nickName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = commentTimeLabel(comment.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = starveilExtraColors.textFaint,
        )
    }
}

/** 正文一行。 */
@Composable
private fun CommentContent(comment: Comment) {
    Text(
        text = comment.content,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xxs),
    )
}

/**
 * 头像。
 *
 * `user_portrait` 可能是站内相对路径或空串，直接交给 Coil 会请求相对 URL 失败；
 * 只认 `https://` 开头的绝对地址，其余用昵称首字占位。
 */
@Composable
private fun DetailCommentAvatar(comment: Comment) {
    val shape = AppShape.full
    val portrait = comment.portrait.takeIf { it.startsWith(HTTPS_PREFIX) }
    if (portrait != null) {
        AsyncImage(
            model = portrait,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(IconSize.lg)
                .clip(shape)
                .background(starveilExtraColors.surfaceCard),
        )
        return
    }
    Box(
        modifier = Modifier
            .size(IconSize.lg)
            .clip(shape)
            .background(starveilExtraColors.surfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = comment.nickName.take(1).ifBlank { AVATAR_FALLBACK },
            style = MaterialTheme.typography.labelMedium,
            color = starveilExtraColors.textMuted,
        )
    }
}

/** 首屏加载：区块内的小加载态，不铺满整页。 */
@Composable
private fun DetailCommentsLoading() {
    StarveilLoading(modifier = Modifier.padding(vertical = Spacing.lg))
}

@Composable
private fun DetailCommentEmpty() {
    Text(
        text = EMPTY_HINT,
        style = MaterialTheme.typography.bodySmall,
        color = starveilExtraColors.textMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.md),
    )
}

@Composable
private fun DetailCommentError(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = starveilExtraColors.textMuted,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text("重试")
        }
    }
}

@Composable
private fun DetailCommentMore(isLoading: Boolean, onLoadMore: () -> Unit) {
    if (isLoading) {
        DetailCommentsLoading()
        return
    }
    TextButton(
        onClick = onLoadMore,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("加载更多")
    }
}

/**
 * 秒级时间戳 → 相对时间。
 *
 * `created_at` 是秒不是毫秒（与 `danmu_time` 同口径）；超过 30 天退到日期，
 * 避免出现「813 天前」这种没人能换算的文案。
 */
private fun commentTimeLabel(
    createdAt: Long,
    nowSeconds: Long = System.currentTimeMillis() / MILLIS_PER_SECOND,
): String {
    if (createdAt <= 0) return ""
    val minutes = (nowSeconds - createdAt) / SECONDS_PER_MINUTE
    return when {
        minutes < 1 -> "刚刚"
        minutes < MINUTES_PER_HOUR -> "$minutes 分钟前"
        minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} 小时前"
        minutes < MINUTES_PER_MONTH -> "${minutes / MINUTES_PER_DAY} 天前"
        else -> DATE_FORMAT.format(Date(createdAt * MILLIS_PER_SECOND))
    }
}

private const val HTTPS_PREFIX = "https://"
private const val AVATAR_FALLBACK = "评"
private const val EMPTY_HINT = "还没有评论，来说两句吧"
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * 60L
private const val MINUTES_PER_MONTH = 30 * MINUTES_PER_DAY

/** 时区跟随设备（`Locale.CHINA` 只决定数字格式），日期展示不参与业务判定。 */
private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
