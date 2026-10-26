package com.starveil.feature.home

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.HomeSpotlight
import com.starveil.core.model.LiveChannel
import org.junit.Assert
import org.junit.Test
import com.starveil.core.data.LivePage

/**
 * 首页内容位测试：编辑精选 / 热度榜 / 猜你喜欢 / 专题 / 直播 / 每日推荐。
 *
 * 这几块都遵守同一条规则——**后台没配或接口失败就降级成空，绝不能把整页变成错误态**，
 * 具体回退到列表切片由 `HomeScreen` 决定，状态只如实反映后端给了什么。
 * 桩数据在 `HomeTestDoubles.kt`。
 */
class HomeViewModelContentTest {

    @get:org.junit.Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `featured from backend fills the section title and items`() {
        val row = vod()
        val repository = FakeVodRepository(
            result = AppResult.Success(listOf(row)),
            featuredResult = AppResult.Success(
                HomeSpotlight(title = "本周精选", items = listOf(row.copy(id = 77, name = "精选影片"))),
            ),
        )
        val viewModel = HomeViewModel(
            repository,
            FakeStartupRepository(AppResult.Success(startupConfig())),
            FakeContinueWatchingRepository(),
        )

        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertEquals("本周精选", state.featured.title)
        Assert.assertEquals(listOf(77), state.featured.items.map { it.id })
    }

    @Test
    fun `empty featured keeps the state empty so the UI can fall back`() {
        // 后台未配置：仓库给空配置，状态如实反映，切片回退由 HomeScreen 决定
        val viewModel = HomeViewModel(
            FakeVodRepository(AppResult.Success(listOf(vod(), vod().copy(id = 2)))),
            FakeStartupRepository(AppResult.Success(startupConfig())),
            FakeContinueWatchingRepository(),
        )

        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertTrue(state.featured.items.isEmpty())
        Assert.assertTrue(state.featured.title.isBlank())
    }

    @Test
    fun `hot from backend fills the heat section`() {
        val row = vod()
        val repository = FakeVodRepository(
            result = AppResult.Success(listOf(row)),
            hotResult = AppResult.Success(
                HomeSpotlight(title = "本周热榜", items = listOf(row.copy(id = 88))),
            ),
        )
        val viewModel = HomeViewModel(
            repository,
            FakeStartupRepository(AppResult.Success(startupConfig())),
            FakeContinueWatchingRepository(),
        )

        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertEquals("本周热榜", state.hot.title)
        Assert.assertEquals(listOf(88), state.hot.items.map { it.id })
    }

    @Test
    fun `empty hot stays empty so the screen decides the fallback`() {
        val viewModel = HomeViewModel(
            FakeVodRepository(AppResult.Success(listOf(vod(), vod().copy(id = 2)))),
            FakeStartupRepository(AppResult.Success(startupConfig())),
            FakeContinueWatchingRepository(),
        )

        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertTrue(state.hot.items.isEmpty())
        Assert.assertTrue(state.guess.items.isEmpty())
    }

    @Test
    fun `hot failure degrades instead of an error page`() {
        val repository = FakeVodRepository(
            result = AppResult.Success(listOf(vod())),
            hotResult = AppResult.Failure(AppError.Network(null)),
        )
        val viewModel = HomeViewModel(
            repository,
            FakeStartupRepository(AppResult.Success(startupConfig())),
            FakeContinueWatchingRepository(),
        )

        // 内容位失败不能把首屏打成错误态
        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertTrue(state.hot.items.isEmpty())
    }

    @Test
    fun `topic and live modules fill their rows`() {
        val viewModel = HomeViewModel(
            FakeVodRepository(AppResult.Success(listOf(vod()))),
            FakeStartupRepository(AppResult.Success(startupConfig())),
            FakeContinueWatchingRepository(),
            FakeTopicRepository(AppResult.Success(listOf(topic("t1"), topic("t2")))),
            FakeLiveRepository(
                AppResult.Success(
                    LivePage(
                        items = listOf(
                            LiveChannel(id = 1L, name = "频道一", cover = null, url = null),
                            LiveChannel(id = 2L, name = "频道二", cover = null, url = null),
                        ),
                        total = 2,
                        groups = emptyList(),
                        hasMore = false,
                    ),
                ),
            ),
        )

        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertEquals(listOf("t1", "t2"), state.topics.map { it.id })
        Assert.assertEquals(listOf(1L, 2L), state.lives.map { it.id })
    }

    @Test
    fun `topic failure degrades to an empty row instead of an error page`() {
        val viewModel = HomeViewModel(
            FakeVodRepository(AppResult.Success(listOf(vod()))),
            FakeStartupRepository(AppResult.Success(startupConfig())),
            FakeContinueWatchingRepository(),
            FakeTopicRepository(AppResult.Failure(AppError.Network(null))),
            FakeLiveRepository(AppResult.Failure(AppError.Network(null))),
        )

        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertTrue(state.topics.isEmpty())
        Assert.assertTrue(state.lives.isEmpty())
    }

    @Test
    fun `push count loads only when the startup switch is on`() {
        val repository = FakeVodRepository(
            result = AppResult.Success(listOf(vod())),
            pushResult = AppResult.Success(3),
        )
        val viewModel = HomeViewModel(
            repository,
            FakeStartupRepository(AppResult.Success(startupConfig(pushEnabled = true))),
            FakeContinueWatchingRepository(),
        )

        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertTrue(state.pushEnabled)
        Assert.assertEquals(3, state.pushCount)
        Assert.assertEquals(1, repository.pushCalls)
    }

    @Test
    fun `push stays at zero when the switch is off`() {
        val repository = FakeVodRepository(
            result = AppResult.Success(listOf(vod())),
            pushResult = AppResult.Success(3),
        )
        val viewModel = HomeViewModel(
            repository,
            FakeStartupRepository(AppResult.Success(startupConfig(pushEnabled = false))),
            FakeContinueWatchingRepository(),
        )

        // 开关关着就不打这个接口，省一次请求
        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertEquals(0, state.pushCount)
        Assert.assertEquals(0, repository.pushCalls)
    }
}
