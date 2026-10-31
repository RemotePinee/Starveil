package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.model.Vod
import com.starveil.core.model.HomeBanner
import com.starveil.core.model.HomeBlock
import com.starveil.core.model.HomeLink
import com.starveil.core.model.HomeSpotlight
import com.starveil.core.model.VodType
import com.starveil.core.model.VodOrder

/**
 * 影片数据来源。UI 只依赖本接口，不依赖 Retrofit 或 DTO。
 */
interface VodRepository {

    suspend fun list(
        type: Int = 0,
        page: Int = 1,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): AppResult<List<Vod>>

    /**
     * 片库筛选列表（`v1.vod` + area / year / lang / class_name / order）。
     *
     * **为什么不给 [list] 加参数**：Kotlin 的 override 必须声明基类函数的全部参数，
     * 加在 [list] 上会让 `feature:detail`、`feature:player` 测试里那几个只实现
     * `list(type, page, limit)` 的 fake 直接编译失败（那两个模块这次不动）。
     * 改成独立方法，参数全带默认值、并在接口里给默认实现（转发到 [list]），
     * 老 fake 一行都不用改——与 [types]、[search] 是同一套约定。
     *
     * 参数语义（后端仍在核，最终以 [DefaultVodRepository.filter] 那一处为准）：
     * - [area] / [year] / [lang] / [className]：空串 = 不限；
     * - [order]：白名单见 [VodOrder]，空串或非法值都落回 `time`。
     */
    suspend fun filter(
        type: Int = 0,
        page: Int = 1,
        limit: Int = DEFAULT_PAGE_SIZE,
        area: String = "",
        year: String = "",
        lang: String = "",
        className: String = "",
        order: String = VodOrder.DEFAULT,
    ): AppResult<List<Vod>> = list(type = type, page = page, limit = limit)

    suspend fun detail(vodId: Int): AppResult<Vod>

    /**
     * 分类列表。给默认实现（空）——其它 feature 的测试 fake 实现本接口时
     * 不必跟着改（AGENTS 之外的务实约定，见 docs/testing.md）。
     */
    suspend fun types(): AppResult<List<VodType>> = AppResult.Success(emptyList())

    /**
     * 关键词搜索；同样给默认实现，避免其它 feature 的 fake 跟着改。
     *
     * [typeId] 非空时把结果限定在该分类内（剧场内的关键词搜索）；null 表示不限分类，
     * 与加这个参数之前的行为一致。
     */
    suspend fun search(
        keyword: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        typeId: Int? = null,
    ): AppResult<List<Vod>> = AppResult.Success(emptyList())

    /** 首页轮播（插件后台配置）。 */
    suspend fun banners(): AppResult<List<HomeBanner>> = AppResult.Success(emptyList())

    /** 首页专区（含影片）。 */
    suspend fun blocks(): AppResult<List<HomeBlock>> = AppResult.Success(emptyList())

    /** 首页快捷入口。 */
    suspend fun links(): AppResult<List<HomeLink>> = AppResult.Success(emptyList())

    /**
     * 首页「编辑精选」。默认实现返回空配置（不回退内容），这样其它 feature 的
     * 测试 fake 不必为一个首页专属区块补实现；回退到列表切片由 UI 决定。
     */
    suspend fun featured(): AppResult<HomeSpotlight> = AppResult.Success(HomeSpotlight())

    /**
     * 首页「热度榜」。与 [featured] 同样给空配置默认实现：
     * 空 [HomeSpotlight.items] 表示后台未配置，UI 回退到列表切片。
     */
    suspend fun hot(): AppResult<HomeSpotlight> = AppResult.Success(HomeSpotlight())

    /** 首页「猜你喜欢」，回退规则同 [hot]。 */
    suspend fun guess(): AppResult<HomeSpotlight> = AppResult.Success(HomeSpotlight())

    /**
     * 按分类取热度榜（`v1.main/hot?type_id=`）。
     *
     * 独立方法而不是给 [hot] 加参数：override 必须声明全部参数，
     * 改 [hot] 会让其它 feature 的测试 fake 全部编译失败。默认实现退回全局。
     */
    suspend fun hotByType(typeId: Int): AppResult<HomeSpotlight> = hot()

    /** 按分类取猜你喜欢，回退规则同 [hotByType]。 */
    suspend fun guessByType(typeId: Int): AppResult<HomeSpotlight> = guess()

    /** 每日推荐条数；默认 0（不冒红点），其它 feature 的 fake 不必实现。 */
    suspend fun push(): AppResult<Int> = AppResult.Success(0)

    /**
     * 同类型相关推荐。参数依据旧详情页 ：
     * 它用 `getSameTypeList(type_id, vod_class, page, limit=3, ...)` 走 `v1.vod`，
     * 保留 type / page / limit 三个语义，class 过滤不再下发（见实现注释）。
     *
     * 默认实现返回空列表：只读列表的测试 fake 不必为一个附加区块补实现；
     * 真实实现见 [DefaultVodRepository.recommend]。
     */
    suspend fun recommend(typeId: Int, limit: Int = DEFAULT_RECOMMEND_LIMIT): AppResult<List<Vod>> =
        AppResult.Success(emptyList())

    /**
     * 首页首帧快照。默认没有——测试替身与其它实现不需要它；
     * [DefaultVodRepository] 返回注入进来的那一份，[HomeViewModel] 构造时同步读取。
     */
    fun homeSnapshot(): HomeSnapshotStore? = null

    /**
     * 上次打开过的这部片（含播放源与剧集）。默认没有；[DefaultVodRepository]
     * 返回注入的快照，[DetailViewModel] 用它铺首帧，避免进来先转圈。
     */
    fun cachedDetail(vodId: Int): Vod? = null

    /** 筛选页首帧（默认条件的第一页）。默认没有；[DefaultVodRepository] 返回注入的快照。 */
    fun cachedFilter(): FilterSnapshot? = null

    /**
     * 已经拉到过的分类列表。
     *
     * 首页为了顶部分类栏一定会拉一次；筛选页复用同一份就能在首帧把筛选条铺出来，
     * 不用再等一次网络（实测那次 147ms 正好落在进场动画里）。
     */
    fun cachedTypes(): List<VodType> = emptyList()

    companion object {
        const val DEFAULT_PAGE_SIZE = 20

        /** 详情页推荐横滑一屏 6 张，够滚两次；旧页固定取 3 条。 */
        const val DEFAULT_RECOMMEND_LIMIT = 6
    }
}
