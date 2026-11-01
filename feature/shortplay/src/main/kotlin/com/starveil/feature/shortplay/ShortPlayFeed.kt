package com.starveil.feature.shortplay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import com.starveil.core.common.ad.AdPlacement
import com.starveil.core.designsystem.component.AdBadge
import com.starveil.core.designsystem.component.rememberScreenImageRequest
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import com.starveil.core.designsystem.token.AppShape
import com.starveil.core.designsystem.component.LocalAdClick
import com.starveil.core.designsystem.component.LocalAdPlacements
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.ShortPlay

private const val LOAD_MORE_PREFETCH = 2

/**
 * 剧场页的一屏内容：正片，或一条信息流直投广告。
 *
 * 为什么要建这一层而不是"到页面里判 if"：广告是插在两次滑动之间的**独立一整屏**，
 * 插进来之后 `plays` 的下标和 pager 的 page 不再相等，凡是还要按 play 定位的地方
 * （`currentPlayId` 回滚、settle 回调、触底加载）都必须走这个映射，否则滑动会错位。
 */
private sealed interface FeedPage {
    /** 广告的 key 跟着它前面那一批正片走，分页追加时不会把已显示的广告顶掉。 */
    val key: String

    data class Play(val play: ShortPlay) : FeedPage {
        override val key: String get() = play.id.toString()
    }

    data class Ad(val creative: AdPlacement, override val key: String) : FeedPage
}

/**
 * 每 [interval] 条正片后面插一屏广告（`feedInterval` 为 0 或没素材时一条不插）。
 *
 * 不在末尾补广告：最后一条正片后面插广告会让人误以为"还有内容"，实际已经到底了。
 */
private fun buildFeedPages(
    plays: List<ShortPlay>,
    creative: AdPlacement?,
    interval: Int,
): List<FeedPage> {
    val every = if (creative != null) interval else 0
    val pages = ArrayList<FeedPage>(plays.size + plays.size / every.coerceAtLeast(1))
    plays.forEachIndexed { index, play ->
        pages += FeedPage.Play(play)
        val isLast = index == plays.lastIndex
        // 拆成两个布尔：三连 && 会撞 detekt 的条件复杂度阈值（3 即失败）
        val hasAdSlot = every > 0 && !isLast
        val reachedInterval = every > 0 && (index + 1) % every == 0
        if (hasAdSlot && reachedInterval) {
            pages += FeedPage.Ad(creative!!, key = "ad_${index + 1}")
        }
    }
    return pages
}

@Composable
internal fun ShortPlayFeed(
    state: ShortPlayUiState.Content,
    actions: ShortPlayActions,
    drawerClosed: Boolean,
    playerContent: @Composable (ShortPlay, Boolean) -> Unit,
    presentation: ShortPlayPresentation = ShortPlayPresentation(),
    /** 当前停的这页是不是广告屏：外层据此收掉顶部菜单/分类/搜索。 */
    onAdVisible: (Boolean) -> Unit = {},
    /**
     * feed 级唯一视频层。非空表示播放面由外层持有：整个 feed 一台播放器、一块 TextureView，
     * 页面自己只画覆盖层与封面，滑页只换媒体项。
     *
     * 参数是"当前落页被整屏广告盖住"：广告占着播放面时停掉内容解码（不释放播放器），
     * 回到正片由那一页重新 prepare。
     */
    videoLayer: (@Composable (Boolean) -> Unit)? = null,
) {
    val feed = LocalAdPlacements.current.feed
    // 素材缺失时 creative 为空，下面的构建函数就不会插任何广告屏（后台没配=不占位）
    val creative = feed.takeIf { it.drawable }
    // 键只取稳定的三个值：pagesVersion 只随 plays 真换内容 +1，落页与进度不动它。
    // 原来拿整个 plays 当键，重组时每次都要对几百条短剧逐字段深比较，滚动中被反复触发。
    val pages = remember(state.pagesVersion, creative, feed.feedInterval) {
        buildFeedPages(state.plays, creative, feed.feedInterval)
    }
    if (pages.isEmpty()) return
    val initialPage = pages.indexOfFirst { (it as? FeedPage.Play)?.play?.id == state.currentPlayId }
    val pager = rememberPagerState(initialPage = initialPage.coerceAtLeast(0)) { pages.size }
    // 初值直接取当前版本：否则首帧 synchronized=false，整页先按"不可播"组合一遍，
    // 等 LaunchedEffect 回写再整页重组，进入剧场白等一轮（实测 50-100ms）。
    var synchronizedVersion by remember(pager) { mutableLongStateOf(state.collectionVersion) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val synchronized = synchronizedVersion == state.collectionVersion
    val stablePage = pager.isStableShortPlayPage()

    val visible = drawerClosed && lifecycle == Lifecycle.State.RESUMED
    // 锁手势只禁滚动：已落页的那页必须继续播放，不能因锁定把 canPlay 打成 false。
    val canPlay = synchronized && visible && (stablePage || presentation.interactionLocked)
    val lockedAdState = rememberLockedAdKey(pager, pages)
    val lockedAdKey = lockedAdState.value
    val covered = pages.getOrNull(pager.settledPage) is FeedPage.Ad

    LaunchedEffect(pager.currentPage, pages) {
        onAdVisible(pages.getOrNull(pager.currentPage) is FeedPage.Ad)
    }
    LaunchedEffect(state.collectionVersion) {
        val target = pages.indexOfFirst { (it as? FeedPage.Play)?.play?.id == state.currentPlayId }
        pager.scrollToPage(target.coerceAtLeast(0))
        synchronizedVersion = state.collectionVersion
    }
    ShortPlayPageWatcher(pager, FeedWatcher(pages, state, actions, synchronized))
    Box(Modifier.fillMaxSize()) {
        // 视频面铺在 pager 下面：整条信息流只创建这一次，换剧/换集都不重建。
        // 铺满整页不再裁底边：让位只发生一次、在页面前景（入口条贴 dock 上沿），
        // 那一条由页面自己的 dock 让位带铺住，抬高时露不出画面。
        FeedVideoLayer(videoLayer, covered)
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { pages[it].key },
            // 相邻页不组合：整个 feed 只有一台播放器，多铺一页不会多一个解码器，
            // 但会让邻居页在手势期间抢同一块 TextureView、并且提前画自己的封面。
            // 滑页期间邻居页只负责滑动动画，落页后由它接管媒体项。
            beyondViewportPageCount = 0,
            // 停在广告页且还没到强制停留时间时锁住翻页
            userScrollEnabled = !presentation.interactionLocked && lockedAdKey == null,
        ) { page ->
            FeedPageContent(
                page = pages[page],
                playing = canPlay && page == pager.settledPage,
                adLock = AdLock(lockedAdKey) { key ->
                    if (lockedAdState.value == key) lockedAdState.value = null
                },
                playerContent = playerContent,
                hasVideoLayer = videoLayer != null,
            )
        }
        ShortPlayFeedStatus(state, actions.onRetry, Modifier.align(Alignment.TopCenter))
    }
}

/**
 * 视频层由外层注入时铺在 pager 下面；null 表示没有 feed 级播放面，页面自己垫封面。
 *
 * 这里不裁底边：裁绘制虽然不改测量，但它和页面前景的上收是同一条让位的第二次实现，
 * 两条边一旦用不同口径算（令牌 vs 实测 dock 高度）就会在入口条下面留出一块纯色。
 * 让位只由页面前景做一次，视频照常铺到屏幕底，多出来的部分压在 dock 与让位带下面。
 */
@Composable
private fun FeedVideoLayer(layer: (@Composable (Boolean) -> Unit)?, covered: Boolean) {
    if (layer != null) layer(covered)
}

/** 一屏内容：正片交给 app 注入的播放器，广告屏画自己的整屏。 */
@Composable
private fun FeedPageContent(
    page: FeedPage,
    playing: Boolean,
    adLock: AdLock,
    playerContent: @Composable (ShortPlay, Boolean) -> Unit,
    hasVideoLayer: Boolean = false,
) {
    when (page) {
        is FeedPage.Play -> Box(Modifier.fillMaxSize()) {
            // 有 feed 级视频层时不再垫封面：视频就在这一层下面，无首帧时封面由页面自己顶。
            if (!hasVideoLayer) ShortPlayBackdrop(page.play)
            playerContent(page.play, playing)
        }

        is FeedPage.Ad -> ShortPlayAdPage(
            creative = page.creative,
            locked = adLock.key == page.key,
            onCountdownFinished = { adLock.onFinished(page.key) },
        )
    }
}

/** 广告屏的强制停留：当前锁着哪一屏，以及倒计时结束时的回调。 */
private class AdLock(val key: String?, val onFinished: (String) -> Unit)

/** 停在"要求强制停留"的广告屏时返回它的 key。 */
private fun lockedAdKeyOf(pages: List<FeedPage>, settled: Int): String? {
    val ad = pages.getOrNull(settled) as? FeedPage.Ad ?: return null
    return if (ad.creative.feedSeconds > 0) ad.key else null
}

@Composable
private fun rememberLockedAdKey(pager: PagerState, pages: List<FeedPage>): MutableState<String?> {
    val locked = remember(pages) { mutableStateOf<String?>(null) }
    LaunchedEffect(pager.settledPage, pages) {
        locked.value = lockedAdKeyOf(pages, pager.settledPage)
    }
    return locked
}
/**
 * 信息流广告整屏：素材铺满、左上角「广告」角标、底部标题与副标题。
 *
 * 三个位置都是踩过的坑：
 * - 角标让开顶部工具栏高度，否则被抽屉按钮压住；
 * - 标题压在图片左下角，但要留出一整个工具栏高度给底部 dock，不然会贴住底栏；
 * - 后台配了强制停留秒数时右上角走倒计时，走完之前不允许往上滑。
 */
@Composable
private fun ShortPlayAdPage(
    creative: AdPlacement,
    locked: Boolean,
    onCountdownFinished: () -> Unit,
) {
    val openLanding = LocalAdClick.current
    val colors = starveilExtraColors
    val seconds = creative.feedSeconds
    // 整屏素材按屏幕像素解码：缓存键不再随父容器约束漂移，同一张广告来回滚动只解一次，
    // 也不会因为约束退化到「原图尺寸」把一张 4000px 的海报整张塞进 Graphics。
    val creativeRequest = rememberScreenImageRequest(creative.imageUrl)
    var remain by remember(creative.imageUrl, seconds) { mutableIntStateOf(seconds) }
    LaunchedEffect(seconds, locked) {
        if (!locked || seconds <= 0) return@LaunchedEffect
        remain = seconds
        while (remain > 0) {
            delay(MILLIS_PER_SECOND)
            remain -= 1
        }
        onCountdownFinished()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfaceCard)
            .clickable { if (creative.clickUrl.isNotBlank()) openLanding(creative.clickUrl) },
    ) {
        AsyncImage(
            model = creativeRequest,
            contentDescription = creative.title.ifBlank { "广告" },
            contentScale = ContentScale.Crop,
            // 竖屏全屏广告按规范铺满整屏（展示面积是横屏的 3 倍），
            // 只靠角标、倒计时、标题各自的半透明底保证可读，不给图片留边。
            modifier = Modifier.fillMaxSize(),
        )
        // 与顶部工具栏同高：抽屉按钮就在那一行里，角标要跟它水平对齐
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .heightIn(min = Spacing.rowHeight)
                // 往下压一点：文字行高比抽屉按钮矮，纯居中会比按钮偏上
                .padding(top = Spacing.xs, end = Spacing.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (locked && remain > 0) {
                Text(
                    text = "${remain}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onImage,
                    // 深色圆底托住白字：浅色素材上白字会直接消失。
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.scrimStrong)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                )
            }
            AdBadge()
        }
        // 底部纵向渐变：图片仍然是满屏，靠这层把文字压暗托住。
        // 这是全屏广告的标准做法，给文字加色块会显得像贴片。
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(Spacing.xxl * 8)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, starveilExtraColors.scrimStrong),
                    ),
                ),
        )
        AdCaption(creative = creative, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** 广告文案：标题两行、副标题一行，整体压在图片左下角并让开底部 dock。 */
@Composable
private fun AdCaption(creative: AdPlacement, modifier: Modifier) {
    val colors = starveilExtraColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageHorizontal)
            // 底部留出工具栏高度：短剧页 dock 是叠在内容上的，不留就会贴着底栏
            .padding(bottom = Spacing.rowHeight + Spacing.xl),
    ) {
        if (creative.title.isNotBlank()) {
            Text(
                text = creative.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onImage,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (creative.subtitle.isNotBlank()) {
            Text(
                text = creative.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onImage,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L

private fun PagerState.isStableShortPlayPage(): Boolean =
    !isScrollInProgress && currentPage == settledPage && currentPageOffsetFraction == 0f

private class FeedWatcher(
    val pages: List<FeedPage>,
    val state: ShortPlayUiState.Content,
    val actions: ShortPlayActions,
    val synchronized: Boolean,
)

@Composable
private fun ShortPlayPageWatcher(pager: PagerState, watcher: FeedWatcher) {
    val pages = watcher.pages
    val state = watcher.state
    val actions = watcher.actions
    val synchronized = watcher.synchronized
    LaunchedEffect(
        pager, pages, state.page, state.isRefreshing,
        state.appendError, state.refreshError, synchronized,
    ) {
        snapshotFlow {
            if (synchronized && pager.isStableShortPlayPage()) pager.settledPage else null
        }.collect { index ->
            val settledIndex = index ?: return@collect
            // 停在广告屏上不改变"当前在看的剧"，也不触发加载更多：广告后面还有内容
            val play = (pages.getOrNull(settledIndex) as? FeedPage.Play)?.play ?: return@collect
            actions.onPageSettled(play.id)
            val remaining = pages.size - 1 - settledIndex
            val canAppend = state.appendError == null && state.refreshError == null && !state.isRefreshing
            if (canAppend && remaining <= LOAD_MORE_PREFETCH) actions.onLoadMore()
        }
    }
}

/** 已有内容刷新不打断观看；只有失败时在顶部安全区下提供轻量重试。 */
@Composable
private fun ShortPlayFeedStatus(
    state: ShortPlayUiState.Content,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = state.refreshError ?: state.appendError ?: return
    Surface(
        modifier = modifier.statusBarsPadding().padding(top = Spacing.rowHeight),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        TextButton(onClick = onRetry) {
            Text("$error · 重试", style = MaterialTheme.typography.labelLarge)
        }
    }
}
