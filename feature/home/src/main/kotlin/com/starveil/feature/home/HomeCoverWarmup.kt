package com.starveil.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import com.starveil.core.designsystem.token.Spacing
import com.starveil.core.model.Vod
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * 首页各分类封面的预热队列。
 *
 * 分类数据本来就是后台预热的（[HomeFeedCache.prefetch]），切 Tab 不转圈了，
 * 但**图还是当场下载 + 解码**：切过去先看到一排空位，再一张张填上去。
 * 这里把每个分类前几张海报排进队列，由页面层提前解好：
 * Coil 的内存缓存键就是 URL（`UriKeyer` 只取 data），所以预热的位图能被卡片直接复用，
 * 切 Tab 那一帧图就在，既不用等网络也不用重新解码。
 */
internal class HomeCoverWarmup {

    private val seen = mutableSetOf<String>()

    private val pending = mutableListOf<String>()

    private val _urls = MutableStateFlow<List<String>>(emptyList())

    /** 排队中的封面。每次发全量，晚到的收集方也能拿到之前那几批。 */
    val urls: StateFlow<List<String>> = _urls.asStateFlow()

    /** 记录一组影片的封面：重复、空串与超出上限的忽略。 */
    fun add(vods: List<Vod>) {
        val posters = vods.take(MAX_COVERS_PER_BATCH)
            .mapNotNull { it.poster?.takeIf(String::isNotBlank) }
            // 重复的封面在去重这一步就淘汰掉，顺手决定了这一批真正要下载的图
            .filter { seen.add(it) }
        val room = MAX_QUEUED_COVERS - pending.size
        if (room <= 0 || posters.isEmpty()) return
        pending += posters.take(room)
        _urls.value = pending.toList()
    }

    private companion object {
        /** 一组最多排几张：榜单一行 + 网格两行，再往下的图用户得先滚动才看得到。 */
        const val MAX_COVERS_PER_BATCH = 6

        /**
         * 队列上限：按分类顺序排，排满为止——用户最先点到的总是靠前的几个 Tab，
         * 更靠后的分类切过去时卡片自己会请求一次。
         */
        const val MAX_QUEUED_COVERS = 96
    }
}

/** 首屏预热几张封面：一屏网格 + 一格余量，启动屏等这几张解完再放行。 */
internal const val FIRST_PAGE_COVERS = 6

/** 首屏图先走：预热让出这几百毫秒，别跟正在上屏的图抢带宽。 */
private const val WARM_START_DELAY_MS = 600L

/** 每像素字节数（ARGB_8888 / 硬件位图都按 4 算）。 */
private const val BYTES_PER_PIXEL = 4

/**
 * 允许占用多少内存缓存预热：全局缓存是可用堆的 8%（见 `StarveilApplication.newImageLoader`），
 * 这里只用一半，剩下的留给正在上屏的图，别把看得见的位图挤出去。
 */
private const val MEMORY_WARM_BUDGET_BYTES = 12L * 1024 * 1024

/** 预热尺寸比实际格子放大一点：格子少算一两个像素时，INEXACT 复用才不会被判成"太小"。 */
private const val WARM_SIZE_SLACK = 1.08f

/**
 * 网格海报的像素宽：与 [PosterRows] 的布局同口径（左右各一档内边距、卡片间一档间隔）。
 *
 * 预热的解码尺寸必须按这个算，解出来的位图才会被卡片判为"够大"。
 */
@Composable
internal fun rememberPosterWidthPx(columns: Int): Int {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val safeColumns = columns.coerceAtLeast(1)
    return remember(safeColumns, configuration.screenWidthDp, density) {
        with(density) {
            val available = configuration.screenWidthDp.dp - Spacing.lg * 2 - Spacing.sm * (safeColumns - 1)
            ((available / safeColumns).toPx() * WARM_SIZE_SLACK).roundToInt()
        }
    }
}

/**
 * 顺序把队列里的封面提前解出来。
 *
 * - **先写内存、后只写磁盘**：卡片请求是 `Precision.INEXACT`，只要缓存里的位图不小于卡片尺寸
 *   就直接命中，所以预热按格子尺寸解码能把"切 Tab 那一帧"变成现成的图。
 *   前 [MEMORY_WARM_BUDGET_BYTES] 张进内存，再往后的只下进磁盘缓存（解出来的位图立刻丢掉），
 *   既不会把内存缓存冲掉，也不会白下载。
 * - **一张一张来**：冷启动那一刻首屏图正在下载，预热抢带宽会让首屏更慢。
 * - 失败不重试：用户真切过去时卡片自己会再请求一次。
 */
@Composable
internal fun PrefetchHomeCovers(urls: StateFlow<List<String>>, posterWidthPx: Int) {
    val context = LocalContext.current
    LaunchedEffect(urls, posterWidthPx) {
        val loader = SingletonImageLoader.get(context)
        val width = posterWidthPx.coerceAtLeast(1)
        val height = (width / POSTER_RATIO).roundToInt()
        val cellSize = Size(width, height)
        val perImageBytes = width.toLong() * height * BYTES_PER_PIXEL
        val memoryQuota = (MEMORY_WARM_BUDGET_BYTES / perImageBytes).toInt().coerceAtLeast(0)
        val downloaded = mutableSetOf<String>()
        var waited = false
        urls.collect { queue ->
            if (queue.isEmpty()) return@collect
            if (!waited) {
                waited = true
                delay(WARM_START_DELAY_MS)
            }
            var inMemory = 0
            for (url in queue.filter(downloaded::add)) {
                val intoMemory = inMemory < memoryQuota
                if (intoMemory) inMemory++
                loader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(cellSize)
                        .memoryCachePolicy(if (intoMemory) CachePolicy.ENABLED else CachePolicy.DISABLED)
                        .build(),
                )
            }
        }
    }
}
