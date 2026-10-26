package com.starveil.feature.home

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.data.ContinueWatchingRepository
import com.starveil.core.data.LivePage
import com.starveil.core.data.LiveRepository
import com.starveil.core.data.StartupRepository
import com.starveil.core.data.TopicRepository
import com.starveil.core.data.VodRepository
import com.starveil.core.common.ad.AdPlacements
import com.starveil.core.model.ContinueWatching
import com.starveil.core.model.HomeModule
import com.starveil.core.model.HomeSpotlight
import com.starveil.core.model.LiveChannel
import com.starveil.core.model.SecondTab
import com.starveil.core.model.SkinId
import com.starveil.core.model.SkinResolution
import com.starveil.core.model.StartupConfig
import com.starveil.core.model.Topic
import com.starveil.core.model.TopicDetail
import com.starveil.core.model.Vod

/**
 * 首页测试共用的桩数据与 fake。
 *
 * 抽成独立文件是两个原因：`HomeViewModelTest` 的类体逼近 detekt 的
 * `LargeClass`（200 行）上限；内容位测试（[HomeViewModelContentTest]）也要复用
 * 同一批 fake，留在类里只能复制一遍。
 */

internal fun startupConfig(
    skin: SkinId = SkinId.DEFAULT,
    columns: Int = StartupConfig.DEFAULT_HOME_COLUMNS,
    modules: List<HomeModule> = HomeModule.entries,
    pushEnabled: Boolean = false,
) = StartupConfig(
    skin = SkinResolution(
        skin = skin,
        fromLocalOverride = false,
        contractBroken = false,
        unknownCode = false,
    ),
    bannerStyleCode = null,
    secondTab = SecondTab.LIVE,
    homeCardColumns = columns,
    appUiHomeSmallIcons = null,
    topBarUsesBanner = false,
    playRecommendEnabled = false,
    ads = AdPlacements(),
    payments = emptyList(),
    searchHot = emptyList(),
    homeModules = modules,
    pushEnabled = pushEnabled,
)

internal fun vod() = Vod(
    id = 1,
    name = "示例影片",
    poster = null,
    remarks = null,
    year = null,
    area = null,
    score = null,
    blurb = null,
    playSources = emptyList(),
)

internal fun continueItem(vodId: Int) = ContinueWatching(
    vodId = vodId,
    vodName = "示例影片 $vodId",
    vodPic = null,
    sourceIndex = 0,
    episodeIndex = 2,
    progressSec = 600,
    updatedAt = 1760000000,
)

internal fun topic(id: String) = Topic(
    id = id,
    name = "专题 $id",
    poster = null,
    blurb = null,
    content = null,
)

internal class FakeVodRepository(
    private val result: AppResult<List<Vod>>,
    private val featuredResult: AppResult<HomeSpotlight> = AppResult.Success(HomeSpotlight()),
    private val hotResult: AppResult<HomeSpotlight> = AppResult.Success(HomeSpotlight()),
    private val guessResult: AppResult<HomeSpotlight> = AppResult.Success(HomeSpotlight()),
    private val pushResult: AppResult<Int> = AppResult.Success(0),
) : VodRepository {
    var pushCalls: Int = 0
        private set

    override suspend fun list(type: Int, page: Int, limit: Int) = result

    override suspend fun detail(vodId: Int) = AppResult.Failure(AppError.Unknown(null))

    override suspend fun featured() = featuredResult

    override suspend fun hot() = hotResult

    override suspend fun guess() = guessResult

    override suspend fun push(): AppResult<Int> {
        pushCalls++
        return pushResult
    }
}

internal class FakeStartupRepository(private val result: AppResult<StartupConfig>) : StartupRepository {
    override suspend fun load(localThemeOverride: String?) = result
}

internal class FakeContinueWatchingRepository(
    private val result: AppResult<List<ContinueWatching>> = AppResult.Success(emptyList()),
) : ContinueWatchingRepository {
    var calls: Int = 0
        private set

    override suspend fun recent(): AppResult<List<ContinueWatching>> {
        calls++
        return result
    }
}

internal class FakeTopicRepository(
    private val result: AppResult<List<Topic>> = AppResult.Success(emptyList()),
) : TopicRepository {
    var calls: Int = 0
        private set

    override suspend fun list(): AppResult<List<Topic>> {
        calls++
        return result
    }

    override suspend fun detail(topicId: String): AppResult<TopicDetail> =
        AppResult.Failure(AppError.Unknown(null))
}

internal class FakeLiveRepository(
    private val result: AppResult<LivePage> = AppResult.Success(
        LivePage(items = emptyList(), total = 0, groups = emptyList(), hasMore = false),
    ),
) : LiveRepository {
    override suspend fun list(page: Int, limit: Int, group: String): AppResult<LivePage> = result
}
