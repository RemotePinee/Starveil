package com.starveil.feature.home

import com.starveil.core.model.ContinueWatching
import com.starveil.core.model.HomeBanner
import com.starveil.core.model.HomeBlock
import com.starveil.core.model.HomeLink
import com.starveil.core.model.HomeModule
import com.starveil.core.model.HomeSpotlight
import com.starveil.core.model.HomeVariant
import com.starveil.core.model.LiveChannel
import com.starveil.core.model.Topic
import com.starveil.core.model.Vod
import com.starveil.core.model.VodType

/**
 * 首页状态。不可变，通过 StateFlow 暴露（AGENTS.md 第 5 节）。
 *
 * [HomeUiState.Content.variant] 来自启动接口的 `app_theme_ui`（经 `Skins.home()` 落成 3 个实现），
 * columns 来自 `ui_home_data`（"2" 两列，其余三列），两者都由 StartupConfig 提供，
 * 页面不做二次判断。
 */
sealed interface HomeUiState {

    /**
     * 首屏骨架。带上热词是为了骨架里的搜索框也显示后台配置的文案，
     * 数据到达后顶部不会从占位文案跳成热词。
     */
    data class Loading(
        val searchHot: List<String> = emptyList(),
        /** 后台下发的模块顺序：骨架按这个顺序摆，没配的模块连占位都不出现。 */
        val modules: List<HomeModule> = HomeModule.entries.toList(),
    ) : HomeUiState

    data class Content(
        val items: List<Vod>,
        val variant: HomeVariant,
        val columns: Int,
        /** 分类行：首页(0) + 后端一级分类。 */
        val types: List<VodType> = emptyList(),
        val selectedTypeId: Int = 0,
        val isRefreshing: Boolean = false,
        /** 正在切分类、目标分类还没有内容：列表区上骨架，别停在上一个分类的画面上。 */
        val isSwitching: Boolean = false,
        val banners: List<HomeBanner> = emptyList(),
        val blocks: List<HomeBlock> = emptyList(),
        val links: List<HomeLink> = emptyList(),
        val modules: List<HomeModule> = HomeModule.entries.toList(),
        /** 「继续观看」：仅当 `home_modules` 含 continue 且后端有记录时非空。 */
        val continueWatching: List<ContinueWatching> = emptyList(),
        /**
         * 「编辑精选」：后台可配置标题与影片；[HomeSpotlight.items] 为空表示后台未配置，
         * 页面自行回退到 [items] 的切片（见 HomeScreen.featuredSection）。
         */
        val featured: HomeSpotlight = HomeSpotlight(),
        /** 「热度榜」：与 [featured] 同构；空表示未配置，页面回退到切片。 */
        val hot: HomeSpotlight = HomeSpotlight(),
        /** 「猜你喜欢」：同上。 */
        val guess: HomeSpotlight = HomeSpotlight(),
        /** 专题横滑：模块关闭或请求失败时为空，整块不渲染。 */
        val topics: List<Topic> = emptyList(),
        /** 直播横滑：同上。 */
        val lives: List<LiveChannel> = emptyList(),
        /** 每日推荐条数；为 0 或 [pushEnabled] 为 false 时顶栏不冒红点。 */
        val pushCount: Int = 0,
        val pushEnabled: Boolean = false,
        /** 搜索框热词（后台 `search_hot`），UI 取第一条，空则用自己的占位文案。 */
        val searchHot: List<String> = emptyList(),
    ) : HomeUiState

    data class Empty(
        val message: String,
        val variant: HomeVariant,
        val columns: Int,
        val types: List<VodType> = emptyList(),
        val selectedTypeId: Int = 0,
        val searchHot: List<String> = emptyList(),
    ) : HomeUiState

    data class Error(val message: String) : HomeUiState
}
