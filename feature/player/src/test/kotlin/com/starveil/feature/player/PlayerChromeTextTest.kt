package com.starveil.feature.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 控制层文案的边界用例：空集、空线路名、集名与线路重名、负数时间、跨小时。 */
class PlayerChromeTextTest {

    @Test
    fun `subtitle keeps only the source name that the title does not already show`() {
        assertEquals("", episodeSubtitleOf(PlayerUiState()))
        // 主标题为空时回落到线路名，副标题不能重复同一句
        assertEquals("", episodeSubtitleOf(PlayerUiState(sourceName = "线路一")))
        assertEquals(
            "线路一",
            episodeSubtitleOf(PlayerUiState(episodeTitle = "第 1 集", sourceName = "线路一")),
        )
        // 后端只给了集名，没有线路：不再补一行"第 1 集"
        assertEquals("", episodeSubtitleOf(PlayerUiState(episodeTitle = "第 1 集")))
    }

    @Test
    fun `format time clamps negative input and pads short durations`() {
        assertEquals("00:00", formatTime(-1_000L))
        assertEquals("00:12", formatTime(12_000L))
        assertEquals("01:05", formatTime(65_000L))
        assertEquals("1:00:05", formatTime(3_605_000L))
    }

    @Test
    fun `speed label keeps the decimal for the preset steps`() {
        assertEquals("1.0x", speedLabel(1f))
        assertEquals("1.25x", speedLabel(1.25f))
        assertEquals("2.0x", speedLabel(2f))
    }

    @Test
    fun `pager height follows each group's real rows and stays continuous across the page flip`() {
        val episodes = List(156) {
            VerticalEpisode(index = it, number = it + 1, title = "第${it + 1}集", url = "")
        }
        // 前 5 组各 30 集（5 行），151–156 只有 1 行：不能再按整季封顶成 5 行
        assertEquals(EPISODE_CELL_HEIGHT * 5 + EPISODE_CELL_GAP * 4, episodePageGridHeight(episodes, 0))
        assertEquals(EPISODE_CELL_HEIGHT, episodePageGridHeight(episodes, 5))
        assertEquals(6, episodePageGridHeights(episodes).size)

        val heights = listOf(EPISODE_CELL_HEIGHT, EPISODE_CELL_HEIGHT * 5)
        assertEquals(heights[0], episodeGridHeightAt(heights, page = 0, offsetFraction = 0f))
        assertEquals(heights[1], episodeGridHeightAt(heights, page = 1, offsetFraction = 0f))
        // 翻页瞬间 currentPage 由 0 变 1、offsetFraction 由 +0.5 变 -0.5：同一位置必须同一高度
        val beforeFlip = episodeGridHeightAt(heights, page = 0, offsetFraction = 0.5f)
        val afterFlip = episodeGridHeightAt(heights, page = 1, offsetFraction = -0.5f)
        assertEquals(beforeFlip, afterFlip)
        assertEquals(EPISODE_CELL_HEIGHT * 6 / 2, beforeFlip)
        assertEquals(0.dp, episodeGridHeightAt(emptyList(), page = 0, offsetFraction = 0f))
    }

    @Test
    fun `video follows finger across page midpoint while old frames never belong to requested page`() {
        val before = verticalVideoTranslationY(0, 0, 0.5f, 1_000)
        val after = verticalVideoTranslationY(0, 1, -0.5f, 1_000)
        assertEquals(-500f, before)
        assertEquals(before, after)
        assertEquals(0f, verticalVideoTranslationY(0, 1, 0f, 1_000, preview = true))
        assertEquals(true, verticalPageShowsVideo(0, 1, 0, false))
        assertEquals(false, verticalPageShowsVideo(1, 1, 0, false))
        assertEquals(false, verticalPageShowsVideo(1, 1, 1, false))
        assertEquals(true, verticalPageShowsVideo(1, 1, 1, true))
        assertEquals(false, verticalPageShowsVideo(1, 1, null, true))
    }

    @Test
    fun `loading phases reflect real readiness and never cover a failed or paused player`() {
        val state = VerticalPlayUiState(isLoading = false, isPlaying = true)
        val options = VerticalPlayOptions()
        assertEquals(
            VerticalLoadingPhase.Details,
            verticalLoadingPhase(state.copy(isLoading = true), options, null, false),
        )
        val pending = options.copy(active = false, accessPending = true)
        assertEquals(VerticalLoadingPhase.Access, verticalLoadingPhase(state, pending, null, false))
        assertEquals(VerticalLoadingPhase.Video, verticalLoadingPhase(state, options, null, false))
        assertEquals(VerticalLoadingPhase.Frame, verticalLoadingPhase(state, options, 0, false))
        assertEquals(VerticalLoadingPhase.Buffering, verticalLoadingPhase(state, options, 0, true))
        assertEquals(true, verticalShowsLoading(state, pending, false))
        assertEquals(false, verticalShowsLoading(state.copy(errorMessage = "failed"), pending, false))
        assertEquals(false, verticalShowsLoading(state.copy(isPlaying = false), options, false))
        assertEquals(false, verticalShowsLoading(state, options.copy(active = false), false))
    }

    @Test
    fun `shortplay errors are readable without claiming automatic source switching`() {
        val code = "ERROR_CODE_IO_BAD_HTTP_STATUS"
        assertEquals("视频暂时加载失败，请重试或切换短剧", playerErrorMessage(code, triedOtherSources = false))
        assertEquals("这条线路被源站拒绝了（HTTP 错误），已自动尝试其它线路", playerErrorMessage(code))
        assertEquals("播放失败，请重试", playerErrorMessage("ERROR_CODE_UNSPECIFIED", triedOtherSources = false))
    }

    @Test
    fun `short play strip hands off between distinct text without overlapping`() {
        assertEquals(1f, previewStripOpacity(0f), 0f)
        assertEquals(0f, seriesStripOpacity(0f), 0f)
        assertEquals(0.5f, previewStripOpacity(0.25f), 0f)
        assertEquals(0f, seriesStripOpacity(0.25f), 0f)
        assertEquals(0f, previewStripOpacity(0.5f), 0f)
        assertEquals(0f, seriesStripOpacity(0.5f), 0f)
        assertEquals(0f, previewStripOpacity(0.75f), 0f)
        assertEquals(0.5f, seriesStripOpacity(0.75f), 0f)
        assertEquals(0f, previewStripOpacity(1f), 0f)
        assertEquals(1f, seriesStripOpacity(1f), 0f)
    }

    @Test
    fun `compact progress keeps one center line so the thumb is concentric with the track`() {
        val height = 24f
        val thumbRadius = 6f
        // 轨道底边 == 触摸区下沿 == "观看完整短剧"/"选集"条顶边：轨道与入口条之间没有空档
        val compact = progressCenterLineY(compact = true, height = height, trackHeight = 4f)
        assertEquals(height, compact + 4f / 2f, 0.001f)
        // 圆点与轨道共用这条线：上半仍留在触摸区内，下半探进入口条顶部，靠层级画在条之上
        assertEquals(true, compact - thumbRadius >= 0f)
        assertEquals(true, compact + thumbRadius > height)
        // 普通播放器的 3dp 轨道仍在触摸区正中，两条线同一水平，不受 compact 影响
        assertEquals(
            height / 2f,
            progressCenterLineY(compact = false, height = height, trackHeight = 3f),
            0.001f,
        )
    }
}
