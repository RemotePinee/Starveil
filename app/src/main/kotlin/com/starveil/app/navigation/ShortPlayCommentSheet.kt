package com.starveil.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.starveil.app.AppContainer
import com.starveil.core.designsystem.LocalLoginPrompt
import com.starveil.core.designsystem.LoginReason
import com.starveil.core.designsystem.component.DampedSheetScope
import com.starveil.core.designsystem.component.StarveilDampedSheet
import com.starveil.core.model.Session
import com.starveil.feature.detail.CommentActions
import com.starveil.feature.detail.CommentInput
import com.starveil.feature.detail.CommentList
import com.starveil.feature.detail.CommentPanelDeps
import com.starveil.feature.detail.CommentPanelEvent
import com.starveil.feature.detail.CommentPanelViewModel
import kotlin.math.roundToInt

private const val COMMENT_POSTED = "评论已发布"

/**
 * 竖屏短剧的整剧评论抽屉。
 *
 * 契约（跨任务）：右栏点「评论」只走 [com.starveil.feature.player.VerticalPlayOptions.onAction]，
 * 由 [VerticalPlayEntry] 就地组合本组件——不导航、不重建播放器、不暂停播放。
 *
 * 面板与状态机都复用 `:feature:detail`（`CommentList` / `CommentInput` / `CommentPanelViewModel`），
 * 评论数据走 `container.commentRepository`，本文件只负责三件事：
 * 1. 按「剧 + 账号」给 ViewModel 做 key：换号不会继承上一个账号的列表与草稿；
 * 2. 把一次性事件翻译成 UI：Toast、以及未登录时弹 `LoginReason.COMMENT` 登录层；
 * 3. 弹层外壳：共用 [StarveilDampedSheet] 的安全区与阻尼，本文件只让评论列表随可见高度收缩，
 *    输入框始终钉在可见底边。
 */
@Composable
internal fun ShortPlayCommentSheet(
    container: AppContainer,
    vodId: Int,
    onDismiss: () -> Unit,
) {
    val session by container.session.collectAsStateWithLifecycle()
    val viewModel = rememberCommentPanelViewModel(container, vodId, session)
    val state by viewModel.state.collectAsStateWithLifecycle()
    CommentSheetEvents(viewModel)
    val actions = CommentActions(
        onExpand = viewModel::toggleExpanded,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onSubmit = viewModel::submit,
    )
    StarveilDampedSheet(onDismissRequest = onDismiss) {
        val sheetScope = this
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fitDampedVisibleHeight(sheetScope),
        ) {
            CommentList(
                state = state,
                actions = actions,
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            )
            key(vodId, session?.userId, session?.userCheck) {
                CommentInput(
                    posting = state.posting,
                    postedCount = state.postedCount,
                    onSubmit = actions.onSubmit,
                )
            }
        }
    }
}

/**
 * 内容高度跟随 sheet 当前可见高度（[DampedSheetScope.visibleHeight]）。
 *
 * 只在 measure 阶段读取：拖拽时本节点重测即可，不会因为高度变化触发整段评论列表重组。
 */
private fun Modifier.fitDampedVisibleHeight(scope: DampedSheetScope): Modifier =
    layout { measurable, constraints ->
        val target = scope.visibleHeight.roundToPx()
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(
            constraints.copy(minHeight = target, maxHeight = target),
        )
        layout(placeable.width, target) { placeable.place(0, 0) }
    }

/** 账号参与 key：换号（含同一账号重新登录后 userCheck 变化）就换一份列表与草稿。 */
@Composable
private fun rememberCommentPanelViewModel(
    container: AppContainer,
    vodId: Int,
    session: Session?,
): CommentPanelViewModel = viewModel(
    key = "short-play-comments-$vodId-${session?.userId.orEmpty()}-${session?.userCheck.orEmpty()}",
    factory = CommentPanelViewModel.factory(
        deps = CommentPanelDeps(
            commentRepository = container.commentRepository,
            authRepository = container.authRepository,
            session = container.session,
        ),
        vodId = vodId,
    ),
)

/** 一次性事件：发布成功与失败都只提示一次；未登录走登录层，不把用户丢在空面板里。 */
@Composable
private fun CommentSheetEvents(viewModel: CommentPanelViewModel) {
    val context = LocalContext.current
    // 登录层宿主随重组重建：只换引用，不让它进 LaunchedEffect 的 key 而重启收集。
    val loginPrompt = rememberUpdatedState(LocalLoginPrompt.current)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CommentPanelEvent.Toast -> context.shortPlayToast(event.message)
                CommentPanelEvent.Posted -> context.shortPlayToast(COMMENT_POSTED)
                CommentPanelEvent.SignInRequired -> loginPrompt.value.show(LoginReason.COMMENT)
            }
        }
    }
}
