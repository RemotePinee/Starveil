package com.starveil.feature.home

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.HomeModule
import com.starveil.core.model.HomeVariant
import com.starveil.core.model.SkinId
import com.starveil.core.model.Skins
import com.starveil.core.model.StartupConfig
import org.junit.Assert
import org.junit.Test

/**
 * 首页状态测试。
 *
 * 重点守两条：启动配置失败必须降级到默认皮肤（历史版本会直接结束进程），
 * 以及 `ui_home_data` 决定的列数要真的传到状态里。
 *
 * 内容位（精选/热度榜/猜你喜欢/专题/直播/推送）的用例在
 * [HomeViewModelContentTest]，桩数据在 `HomeTestDoubles.kt`。
 */
class HomeViewModelTest {

    @get:org.junit.Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun `startup config drives skin variant and columns`() {
        val startup = FakeStartupRepository(
            result = AppResult.Success(startupConfig(skin = SkinId.BILIBILI, columns = 2)),
        )
        val viewModel = HomeViewModel(
            FakeVodRepository(AppResult.Success(listOf(vod()))),
            startup,
            FakeContinueWatchingRepository(),
        )

        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertEquals(HomeVariant.CARD_GRID_D, state.variant)
        Assert.assertEquals(2, state.columns)
    }

    @Test
    fun `startup failure degrades to default skin instead of crashing`() {
        val startup = FakeStartupRepository(result = AppResult.Failure(AppError.Network(null)))
        val viewModel = HomeViewModel(
            FakeVodRepository(AppResult.Success(listOf(vod()))),
            startup,
            FakeContinueWatchingRepository(),
        )

        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertEquals(Skins.home(SkinId.DEFAULT), state.variant)
        Assert.assertEquals(StartupConfig.DEFAULT_HOME_COLUMNS, state.columns)
    }

    @Test
    fun `empty list becomes empty state`() {
        val viewModel = HomeViewModel(
            FakeVodRepository(AppResult.Success(emptyList())),
            FakeStartupRepository(AppResult.Success(startupConfig())),
            FakeContinueWatchingRepository(),
        )

        Assert.assertTrue(viewModel.state.value is HomeUiState.Empty)
    }

    @Test
    fun `list failure becomes readable error`() {
        val viewModel = HomeViewModel(
            FakeVodRepository(AppResult.Failure(AppError.Network(null))),
            FakeStartupRepository(AppResult.Success(startupConfig())),
            FakeContinueWatchingRepository(),
        )

        val state = viewModel.state.value as HomeUiState.Error
        Assert.assertTrue(state.message.contains("网络"))
    }

    @Test
    fun `continue module loads records into the content state`() {
        val records = FakeContinueWatchingRepository(
            result = AppResult.Success(listOf(continueItem(vodId = 101), continueItem(vodId = 102))),
        )
        val viewModel = HomeViewModel(
            FakeVodRepository(AppResult.Success(listOf(vod()))),
            FakeStartupRepository(AppResult.Success(startupConfig())),
            records,
        )

        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertEquals(listOf(101, 102), state.continueWatching.map { it.vodId })
        Assert.assertEquals(1, records.calls)
    }

    @Test
    fun `continue failure keeps the page content with an empty row`() {
        val viewModel = HomeViewModel(
            FakeVodRepository(AppResult.Success(listOf(vod()))),
            FakeStartupRepository(AppResult.Success(startupConfig())),
            FakeContinueWatchingRepository(result = AppResult.Failure(AppError.Network(null))),
        )

        // 辅助数据失败不能把整页变成错误态，只是不渲染「继续观看」
        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertTrue(state.continueWatching.isEmpty())
    }

    @Test
    fun `continue module absent skips the request entirely`() {
        val records = FakeContinueWatchingRepository(
            result = AppResult.Success(listOf(continueItem(vodId = 101))),
        )
        val viewModel = HomeViewModel(
            FakeVodRepository(AppResult.Success(listOf(vod()))),
            FakeStartupRepository(
                AppResult.Success(startupConfig(modules = HomeModule.entries - HomeModule.CONTINUE)),
            ),
            records,
        )

        val state = viewModel.state.value as HomeUiState.Content
        Assert.assertTrue(state.continueWatching.isEmpty())
        Assert.assertEquals(0, records.calls)
    }
}
