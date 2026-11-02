package com.starveil.feature.shortplay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.starveil.core.designsystem.token.Motion
import com.starveil.core.model.ShortPlayType
import kotlinx.coroutines.launch

/**
 * 左右横滑切分类：每个分类一页，页里就是当前这套竖屏 feed。
 *
 * 只有 owner 那一页组合 feed，其余页交给 [ShortPlayPagerPages.placeholder] 画该分类的占位：
 * 一页 feed 就是一份列表 + 一屏封面 + 一套播放器内容，多组合一页是双倍开销，
 * 所以 `beyondViewportPageCount = 0`。
 *
 * owner 落页后换页那一帧**不能硬切**：占位 ↔ feed 直接互换就是闪一下。这里让占位垫在
 * feed 底下，owner 页用 120ms 的 alpha 淡入盖上来——"模糊底 → 内容"是渐变出来的。
 *
 * 不用 `Crossfade`：那个会在过渡期间同时组合**两页 feed**，两套播放器内容一起活着，
 * 切分类时会出现两个画面源抢同一台播放器。这里内容只在 owner 页组合。
 */
@Composable
internal fun ShortPlayCategoryPager(
    pager: PagerState,
    ownerPage: Int,
    enabled: Boolean,
    pages: ShortPlayPagerPages,
) {
    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 0,
        userScrollEnabled = enabled,
    ) { index ->
        val isOwner = index == ownerPage
        // 不属于本页分类的 state 不上屏：feed 不组合，浮层（右栏/入口条/标题/进度）也就不存在。
        val onScreen = isOwner && pages.onScreen(index)
        // 空 / 失败是整屏独占的文案：撤掉占位层，别让分类名叠在文案上。
        val notice = onScreen && pages.page.state.isFullScreenNotice
        val ownerAlpha by animateFloatAsState(
            targetValue = if (isOwner) 1f else 0f,
            animationSpec = Motion.short,
            label = "shortplay-page-owner",
        )
        Box(Modifier.fillMaxSize()) {
            // 占位垫底：非 owner 页看到的就是它；owner 页淡入那 120ms 靠它兜底，否则露出页面底色。
            if (!notice) pages.placeholder(index)
            if (onScreen) {
                Box(Modifier.fillMaxSize().alpha(ownerAlpha)) {
                    ShortPlayPageContent(pages.page)
                }
            }
        }
    }
}

/** 一次算好每页的占位与该页要不要上屏：判据只有 [ShortPlayUiState] 与分类 id 两个来源。 */
internal fun buildPagerPages(page: ShortPlayPageUi, categories: List<ShortPlayType>): ShortPlayPagerPages =
    ShortPlayPagerPages(
        page = page,
        placeholder = { index ->
            ShortPlayCategoryPlaceholder()
        },
        onScreen = { index -> page.state.belongsTo(categories.getOrNull(index)?.id) },
    )

/**
 * 点击 tab 的动作：让 pager 动画滚到目标页，数据先发起一拍。
 *
 * 选中态与下划线都只认 pager 位置——这里如果直接改选中态，就会和 pager 各说各话。
 */
@Composable
internal fun rememberTypeSelect(
    pager: PagerState,
    categories: List<ShortPlayType>,
    onSelect: (Int) -> Unit,
): (Int) -> Unit {
    val scope = rememberCoroutineScope()
    return { typeId ->
        val index = categories.indexOfFirst { it.id == typeId }
        if (index >= 0) scope.launch { pager.animateScrollToPage(index) }
        onSelect(typeId)
    }
}

/** 顶部横条与左右翻页共用的 pager：页数 = 分类数（后台一个都没配时退成一页 0）。 */
@Composable
internal fun rememberShortPlayCategoryPager(
    categories: List<ShortPlayType>,
    selectedTypeId: Int,
): PagerState = rememberPagerState(
    // 初始页 = 当前选中分类的下标：进页时顶部第一个 chip 就是正在显示的那一页。
    initialPage = categories.indexOfFirst { it.id == selectedTypeId }.coerceAtLeast(0),
) { categories.size.coerceAtLeast(1) }

/**
 * 当前挂 feed 的那一页：滑动全程锁住，松手落页才换。
 *
 * 只在 `isScrollInProgress == false` 时把 `currentPage` 收进 owner。不能用连续位置四舍五入：
 * 那样在 0.5 处左右两页的「内容/占位」角色会瞬间对调，屏幕上就是一次硬切，回退再对调回来。
 * 代价是新页在落页前只有占位；由 [ShortPlayCategoryPager] 的 alpha 淡入把这一帧盖成渐进切换。
 */
@Composable
internal fun rememberCategoryOwnerPage(pager: PagerState): Int {
    var owner by remember(pager) { mutableIntStateOf(pager.currentPage) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.isScrollInProgress to pager.currentPage }
            .collect { (scrolling, page) -> if (!scrolling) owner = page }
    }
    return owner
}
