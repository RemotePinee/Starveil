package com.starveil.feature.user

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Bell
import com.adamglin.phosphoricons.regular.Palette
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.starveil.core.designsystem.LocalDockInset
import com.starveil.core.designsystem.LocalSkinSelection
import com.starveil.core.designsystem.SkinPickerSheet
import com.starveil.core.designsystem.component.StarveilLoadingScreen
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.token.IconSize
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ContinueWatching
/**
 * 用户中心：插画背景顶到状态栏做成沉浸顶栏，内容面板压在它上层、上沿圆角露出图。
 *
 * 信息层级（自上而下）：
 * 1. 顶栏（分享 / 皮肤）+ 头像昵称 + 会员状态；
 * 2. 资料数值（积分 / 金币 / 观看时长）；
 * 3. 会员卡（后台关闭会员时换成"暂未开放"形态，卡常驻、不出按钮）；
 * 4. 观看记录（横滑进度卡片）；
 * 5. 常用工具（收藏 / 金币 / 设置 / 反馈）；
 * 6. 退出登录。
 *
 * 原来"四个圆形快捷入口 + 功能列表"是两段并列平铺，收藏与金币被拆到两个地方；
 * 现在把动作类入口收进一组，把观看记录提成带进度的一区，页面只剩一条主线。
 */
@Composable
fun UserCenterScreen(
    viewModel: UserCenterViewModel,
    actions: UserCenterActions,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shareConfirm by viewModel.shareConfirm.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    UserCenterEvents(viewModel = viewModel, snackbar = snackbarHostState)
    LifecycleResumeEffect(viewModel) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }
    // 分享信息到位后画海报、拉系统分享面板；积分等用户回到前台确认后再发
    UserShareLauncher(viewModel = viewModel)
    // 重试永远指回本页的刷新：外面不需要知道这件事，也就不必传进来
    val resolved = actions.copy(onRetry = viewModel::refresh)
    var showSkinPicker by remember { mutableStateOf(false) }
    val skinSelection = LocalSkinSelection.current
    UserCenterContent(
        state = state,
        actions = resolved,
        snackbar = snackbarHostState,
        onTopAction = { action ->
            when (action) {
                UserCenterTopAction.Messages -> actions.onOpenMessages()
                UserCenterTopAction.Skin -> showSkinPicker = true
                UserCenterTopAction.Share -> viewModel.share()
            }
        },
    )
    if (showSkinPicker) {
        SkinPickerSheet(
            selected = skinSelection.current,
            onSelect = { code ->
                showSkinPicker = false
                skinSelection.select(code)
            },
            onDismiss = { showSkinPicker = false },
        )
    }
    if (shareConfirm) {
        ShareRewardDialog(
            onConfirm = viewModel::confirmShare,
            onDismiss = viewModel::dismissShareConfirm,
        )
    }
}

/** 事件分流：分享结果走系统 Toast，其余走底部 snackbar。 */
@Composable
private fun UserCenterEvents(viewModel: UserCenterViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            // 分享结果走系统 Toast：底部再弹一条会压在会员卡和底栏上，很丑
            if (event.asToast) {
                Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            } else {
                snackbar.showSnackbar(event.text)
            }
        }
    }
}

/**
 * 分享海报：链接到位后画图并拉起系统分享面板。
 *
 * 配色取当前主题令牌；chooser 没有结果回调，等用户回到前台确认后再发奖（见 onResumed）。
 */
@Composable
private fun UserShareLauncher(viewModel: UserCenterViewModel) {
    val context = LocalContext.current
    val accent = starveilExtraColors.accent.toArgb()
    val posterBg = MaterialTheme.colorScheme.background.toArgb()
    val posterFg = MaterialTheme.colorScheme.onSurface.toArgb()
    LaunchedEffect(viewModel, accent, posterBg, posterFg) {
        viewModel.shareLinks.collect { link ->
            val poster = SharePoster.create(
                context = context,
                content = PosterContent(
                    title = link.title,
                    description = link.description,
                    url = link.url,
                ),
                palette = PosterPalette(
                    accent = accent,
                    background = posterBg,
                    foreground = posterFg,
                ),
            )
            if (poster == null) {
                viewModel.notifyShareFailed()
                return@collect
            }
            // 拉不起来（没有能接收 ACTION_SEND 的应用）不发奖；拉起来也只记一笔待确认——
            // chooser 没有结果回调，等用户回到前台确认后再发（见 onResumed）
            if (SharePoster.share(context, poster, link.url, link.title)) {
                viewModel.onShareLaunched()
            } else {
                viewModel.notifyShareLaunchFailed()
            }
        }
    }
}

/** 顶栏三个入口：消息 / 换主题皮肤 / 分享海报。合成一个回调，必填参数才降得下来。 */
private enum class UserCenterTopAction { Messages, Skin, Share }

/** 主体：加载态 / 英雄区 + 内容面板 / 底部提示。 */
@Composable
private fun UserCenterContent(
    state: UserCenterUiState,
    actions: UserCenterActions,
    snackbar: SnackbarHostState,
    onTopAction: (UserCenterTopAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (state.isLoading) {
            StarveilLoadingScreen()
        } else {
            val scrollState = rememberScrollState()
            // 0 = 还没滚，状态栏下面是插图；1 = 标题已经顶到状态栏，需要一层底色压住。
            // 距离取标题行高：滚过一行就压满，再滚只是内容从底下走。
            val density = LocalDensity.current
            val scrimProgress by remember {
                derivedStateOf {
                    (scrollState.value / with(density) { Spacing.rowHeight.toPx() }).coerceIn(0f, 1f)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                UserCenterHero(
                    state = state,
                    actions = actions,
                    onTopAction = onTopAction,
                )
                UserCenterPanel(
                    state = state,
                    actions = actions,
                    modifier = Modifier.floatUp(SHEET_RADIUS),
                )
                // dock 铺在页面之上：整页铺到屏幕底，末尾补一段让内容滚得出来。
                Spacer(Modifier.height(LocalDockInset.current))
            }
            // 状态栏那一条的渐变遮罩：标题和系统时间不会再糊在一起，
            // 插图仍然从它底下滚过去（用户选的是"标题随滚动 + 状态栏渐变"）。
            UserCenterStatusScrim(progress = scrimProgress)
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * 状态栏那一条的渐变遮罩。
 *
 * 标题随内容滚动，滚到顶会压到系统时间上；这层从页面底色渐到透明，只压在状态栏那一条，
 * 插图仍从它下面滚过去。透明度由滚动量线性驱动，本身就是连续量，不加额外动画。
 */
@Composable
private fun UserCenterStatusScrim(progress: Float) {
    if (progress <= 0f) return
    val density = LocalDensity.current
    val barHeight = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val veil = MaterialTheme.colorScheme.background.copy(alpha = STATUS_SCRIM_ALPHA * progress)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight + Spacing.sm)
            .background(Brush.verticalGradient(0f to veil, 1f to Color.Transparent)),
    )
}

/** 遮罩最深处的不透明度：再多就把插图那条边也压掉了。 */
private const val STATUS_SCRIM_ALPHA = 0.92f
@Composable
private fun UserCenterHero(
    state: UserCenterUiState,
    actions: UserCenterActions,
    onTopAction: (UserCenterTopAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().fogBackdrop()) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = Spacing.pageHorizontal)
                .padding(top = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            UserCenterTitleBar(unread = state.unreadMessages, onAction = onTopAction)
            UserProfileBlock(
                state = state,
                onLoginClick = actions.onLoginClick,
            )
            UserCenterDataRow(
                metrics = state.metrics,
                onEntryClick = actions.onEntryClick,
            )
            // 会员卡跨在雾带与面板的交界上；卡常驻，占位固定，不随会员体系开关收窄
            Spacer(modifier = Modifier.height(SHEET_RADIUS + MEMBER_FLOAT))
        }
    }
}
@Composable
private fun UserCenterPanel(
    state: UserCenterUiState,
    actions: UserCenterActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.background,
                shape = AppShape.sheetTopLarge,
            )
            .padding(horizontal = Spacing.pageHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        // 会员卡常驻：后台 member_enabled = 0 时只是换成"暂未开放"形态，不整块消失
        MemberCard(
            state = state,
            onEntryClick = actions.onEntryClick,
            modifier = Modifier.floatUp(MEMBER_FLOAT),
        )
        val message = state.errorMessage
        if (message != null) {
            SyncErrorBanner(message = message, onRetry = actions.onRetry)
        }
        val seeAll: () -> Unit = { actions.onEntryClick(ENTRY_HISTORY) }
        UserCenterWatchHistory(
            input = WatchHistoryInput(
                items = state.history,
                emptyHint = state.historyEmptyHint,
                onItemClick = actions.onHistoryClick,
                onSeeAll = seeAll,
                onEmptyClick = if (state.isLoggedIn) seeAll else actions.onLoginClick,
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SectionTitle(text = SECTION_TOOLS)
            UserCenterTools(
                onEntryClick = actions.onEntryClick,
                goldLabel = state.metrics.goldShown,
            )
        }
        if (state.isLoggedIn) {
            SignOutRow(onLogoutClick = actions.onLogoutClick)
        }
        Spacer(modifier = Modifier.height(Spacing.xl))
    }
}

@Composable
private fun UserCenterTitleBar(
    unread: Int,
    onAction: (UserCenterTopAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(
            text = TITLE,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(WEIGHT_FULL),
        )
        BellAction(unread = unread, onClick = { onAction(UserCenterTopAction.Messages) })
        IconButton(onClick = { onAction(UserCenterTopAction.Share) }) {
            Icon(
                imageVector = PhosphorIcons.Regular.ShareNetwork,
                contentDescription = ACTION_SHARE,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(IconSize.md),
            )
        }
        IconButton(onClick = { onAction(UserCenterTopAction.Skin) }) {
            Icon(
                imageVector = PhosphorIcons.Regular.Palette,
                contentDescription = ACTION_THEME,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(IconSize.md),
            )
        }
    }
}

/**
 * 铃铛：放在标题栏右侧、分享与主题之前。
 *
 * 有未读才在右上角点一颗红点；没未读时只有图标本身，不占额外空间，
 * 也不会像常驻角标那样把标题栏撑乱。
 */
@Composable
private fun BellAction(unread: Int, onClick: () -> Unit) {
    Box {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = PhosphorIcons.Regular.Bell,
                contentDescription = ACTION_MESSAGES,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(IconSize.md),
            )
        }
        if (unread > 0) {
            // 红点贴图标右上角，不是贴按钮盒的右上角：
            // 图标 24dp 居中在 48dp 触控盒里，右上角距盒顶/右各 12dp，
            // 所以红点要按「顶 8dp、右 12dp」摆，才落在图标肩上一个点的距离
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = Spacing.sm, end = Spacing.md)
                    .size(Spacing.xs)
                    .clip(AppShape.full)
                    .background(MaterialTheme.colorScheme.error),
            )
        }
    }
}

/** 向上探出 [overlap]，占位高度同步减掉，不留空洞。 */
private fun Modifier.floatUp(overlap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = overlap.roundToPx()
    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) {
        placeable.placeRelative(0, -shift)
    }
}

/** 区块小标题：列表自带 16dp 横向留白，所以这里不再重复加。积分中心也用它。 */
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = Spacing.sm),
    )
}

/** 面板上沿圆角，和 [AppShape.sheetTopLarge] 的 32dp 对齐。 */
private val SHEET_RADIUS = Spacing.xxl
/** 会员卡向上探出雾带的量；雾带底部占位固定是 [SHEET_RADIUS] + 本值。 */
private val MEMBER_FLOAT = Spacing.xxl + Spacing.md
private const val TITLE = "我的"
private const val ACTION_SHARE = "分享"
private const val ACTION_MESSAGES = "消息"
private const val ACTION_THEME = "主题"
private const val WEIGHT_FULL = 1f
private const val SECTION_TOOLS = "常用工具"
private const val ENTRY_HISTORY = "观看记录"
