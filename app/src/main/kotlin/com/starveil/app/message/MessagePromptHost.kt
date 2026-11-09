package com.starveil.app.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.app.ads.LocalSplashActive
import com.starveil.app.dialog.AppDialogKind
import com.starveil.app.dialog.DialogRequest
import com.starveil.app.dialog.DialogScheduler
import com.starveil.core.common.AppResult
import com.starveil.core.data.MessageRepository
import com.starveil.core.model.AppMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动后的第一条公告/推送。
 *
 * 三个约束：
 * 1. **进首页之后才弹**——冷启动时用户还在看开屏和骨架，弹窗压在加载上既看不到内容
 *    也不知道点进去会去哪；[onHome] 为 true 才入队。
 * 2. **只弹未读里最新一条**——连弹三条是灾难，剩下的进消息中心，铃铛上留红点。
 * 3. **排队**——交给 `DialogScheduler`，和隐私同意、版本更新共用一个队；
 *    定时器同时成立时按优先级一个一个来，不会叠在一起。
 */
@Composable
internal fun MessagePromptHost(
    repository: MessageRepository,
    scheduler: DialogScheduler,
    onHome: Boolean,
    onAction: (AppMessage) -> Unit,
) {
    var message by remember { mutableStateOf<AppMessage?>(null) }
    var request by remember { mutableStateOf<DialogRequest?>(null) }
    val scope = rememberCoroutineScope()
    // 开屏层还在时绝不入队：那会儿首页只是被盖在下面，用户看到的还是黑屏
    val splashActive = LocalSplashActive.current

    LaunchedEffect(onHome, splashActive) {
        if (!onHome || splashActive) return@LaunchedEffect
        // 开屏刚退场的那一帧首页还没画完，再让一个转场的时间，避免弹窗压在空白上
        delay(STARTUP_GRACE_MS)
        val result = repository.list(page = 1, size = PAGE_SIZE)
        if (result !is AppResult.Success) return@LaunchedEffect
        val unread = result.value.items.firstOrNull { !it.read } ?: return@LaunchedEffect
        val queued = DialogRequest(AppDialogKind.Message, "message:${unread.id}")
        message = unread
        request = queued
        scheduler.offer(queued)
    }

    val shown = message
    val queued = request
    val current by scheduler.current.collectAsStateWithLifecycle()
    // 还没轮到自己：什么都不画，请求留在队里等前面的弹窗处理完
    if (shown == null) return
    if (queued == null || current?.key != queued.key) return

    MessagePrompt(
        message = shown,
        onDismiss = {
            scheduler.complete(queued)
            message = null
            // 已读标记失败不回滚：用户已经看过了，下次打开再纠正比"点了又弹"体验好
            scope.launch { repository.markRead(listOf(shown.id)) }
        },
        onAction = { opened ->
            scheduler.complete(queued)
            message = null
            scope.launch { repository.markRead(listOf(opened.id)) }
            onAction(opened)
        },
    )
}

/** 只取一条：弹窗一次只该弹一条，多的交给消息中心。 */
private const val PAGE_SIZE = 1

/** 开屏退场到首页首帧之间留的余量：比开屏淡出动画略长一点。 */
private const val STARTUP_GRACE_MS = 400L
