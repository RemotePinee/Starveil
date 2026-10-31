package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.model.Vod
import com.starveil.core.model.HomeBanner
import com.starveil.core.model.HomeBlock
import com.starveil.core.model.HomeLink
import com.starveil.core.model.HomeSpotlight
import com.starveil.core.model.VodType
import com.starveil.core.model.VodOrder
import com.starveil.core.network.HomeContentApi
import com.starveil.core.network.dto.VodDto
import com.starveil.core.network.mapper.toDomain
import com.starveil.core.network.StarveilApi
import com.starveil.core.network.VodSearchApi
import com.starveil.core.network.VodTypeApi
import com.starveil.core.network.mapper.toDomain

/**
 * 影片仓库。异常收敛交给 [apiCall]，本类只做"响应壳 → 领域模型"的判定。
 */
class DefaultVodRepository(
    private val api: StarveilApi,
    /** 分类接口；为 null 时 [types] 返回空列表（测试 fake 不必实现它）。 */
    private val typeApi: VodTypeApi? = null,
    /** 搜索接口；同理，测试里可以为 null。 */
    private val searchApi: VodSearchApi? = null,
    /** 首页内容位接口；为 null 时下面四个方法返回空配置（测试 fake 不必实现）。 */
    private val homeApi: HomeContentApi? = null,
    /**
     * 首页首帧快照；为 null 时不落盘（测试与其它入口构造本类时不必提供）。
     * 写入点都在接口成功分支里：失败不覆盖快照——上一次的好数据比这次的错误更有用。
     */
    private val snapshot: HomeSnapshotStore? = null,
    /** 详情页快照；为 null 时不落盘（测试不提供）。 */
    private val detailSnapshot: DetailSnapshotStore? = null,
    /** 筛选页快照；同上。 */
    private val filterSnapshot: FilterSnapshotStore? = null,
) : VodRepository {

    override fun homeSnapshot(): HomeSnapshotStore? = snapshot

    override fun cachedDetail(vodId: Int): Vod? = detailSnapshot?.load(vodId)

    override fun cachedFilter(): FilterSnapshot? = filterSnapshot?.snapshot()

    /**
     * 最近一次拉到的分类：首页已经拉过一次，筛选页就不必再等同一份网络数据。
     * 只活在仓库实例里，不落盘——分类随启动配置走，进程重启跟着重拉一次即可。
     */
    private var typesCache: List<VodType> = emptyList()

    override fun cachedTypes(): List<VodType> = typesCache

    /**
     * 只把「默认条件」的第一页写进筛选快照：那正是每次点「筛选」第一眼看到的一屏。
     *
     * 条件拆成两段返回，避免 detekt 的 ComplexCondition（阈值 3）。
     */
    private fun saveDefaultFilterPage(type: Int, page: Int, filters: List<String>, list: List<VodDto>) {
        if (page != 1 || type != 0) return
        if (filters.any { it.isNotBlank() }) return
        filterSnapshot?.saveItems(list)
    }

    override suspend fun banners(): AppResult<List<HomeBanner>> {
        val client = homeApi ?: return AppResult.Success(emptyList())
        return apiCall { client.banners() }.flatMap { envelope ->
            envelope.data?.let { data ->
                snapshot?.saveBanners(data.list)
                AppResult.Success(data.list.map { dto -> dto.toDomain() })
            }
                ?: businessFailure(envelope.code, envelope.msg)
        }
    }

    override suspend fun blocks(): AppResult<List<HomeBlock>> {
        val client = homeApi ?: return AppResult.Success(emptyList())
        return apiCall { client.blocks() }.flatMap { envelope ->
            envelope.data?.let { data ->
                snapshot?.saveBlocks(data.list)
                AppResult.Success(data.list.map { dto -> dto.toDomain() })
            }
                ?: businessFailure(envelope.code, envelope.msg)
        }
    }

    override suspend fun links(): AppResult<List<HomeLink>> {
        val client = homeApi ?: return AppResult.Success(emptyList())
        return apiCall { client.links() }.flatMap { envelope ->
            envelope.data?.let { data ->
                snapshot?.saveLinks(data.list)
                AppResult.Success(data.list.map { dto -> dto.toDomain() })
            }
                ?: businessFailure(envelope.code, envelope.msg)
        }
    }

    override suspend fun featured(): AppResult<HomeSpotlight> {
        val client = homeApi ?: return AppResult.Success(HomeSpotlight())
        return apiCall { client.featured() }.flatMap { envelope ->
            envelope.data?.let { data ->
                snapshot?.saveFeatured(data)
                AppResult.Success(data.toDomain())
            }
                ?: businessFailure(envelope.code, envelope.msg)
        }
    }

    override suspend fun hot(): AppResult<HomeSpotlight> {
        val client = homeApi ?: return AppResult.Success(HomeSpotlight())
        return apiCall { client.hot() }.flatMap { envelope ->
            envelope.data?.let { data ->
                snapshot?.saveHot(data)
                AppResult.Success(data.toDomain())
            }
                ?: businessFailure(envelope.code, envelope.msg)
        }
    }

    override suspend fun guess(): AppResult<HomeSpotlight> {
        val client = homeApi ?: return AppResult.Success(HomeSpotlight())
        return apiCall { client.guess() }.flatMap { envelope ->
            envelope.data?.let { data ->
                snapshot?.saveGuess(data)
                AppResult.Success(data.toDomain())
            }
                ?: businessFailure(envelope.code, envelope.msg)
        }
    }

    override suspend fun hotByType(typeId: Int): AppResult<HomeSpotlight> {
        val client = homeApi ?: return AppResult.Success(HomeSpotlight())
        return apiCall { client.hot(typeId) }.flatMap { envelope ->
            envelope.data?.let { AppResult.Success(it.toDomain()) }
                ?: businessFailure(envelope.code, envelope.msg)
        }
    }

    override suspend fun guessByType(typeId: Int): AppResult<HomeSpotlight> {
        val client = homeApi ?: return AppResult.Success(HomeSpotlight())
        return apiCall { client.guess(typeId) }.flatMap { envelope ->
            envelope.data?.let { AppResult.Success(it.toDomain()) }
                ?: businessFailure(envelope.code, envelope.msg)
        }
    }

    override suspend fun push(): AppResult<Int> {
        val client = homeApi ?: return AppResult.Success(0)
        return apiCall { client.push() }.flatMap { envelope ->
            // 负数没有意义（后端异常时可能给 -1），一律当"没有新推荐"
            envelope.data?.let { AppResult.Success(it.count.coerceAtLeast(0)) }
                ?: businessFailure(envelope.code, envelope.msg)
        }
    }

    override suspend fun list(type: Int, page: Int, limit: Int): AppResult<List<Vod>> =
        apiCall { api.listVod(type = type, page = page, limit = limit) }
            .flatMap { envelope ->
                envelope.data
                    ?.let { pageDto ->
                        // 只存默认分类（type=0）的第一页：切到别的分类后冷启动应回到「全部」
                        if (page == 1 && type == 0) snapshot?.saveItems(pageDto.list)
                        AppResult.Success(pageDto.list.map { dto -> dto.toDomain() })
                    }
                    ?: businessFailure(envelope.code, envelope.msg)
            }

    /**
     * 片库筛选。**筛选参数的唯一拼装点**：query 名、空值语义与排序白名单都在这里
     * 落地——后端按 macCMS 源码微调参数时只改这一处，页面和测试都跟着
     * [VodRepository.filter] 的签名走。
     *
     * 参数名对齐 macCMS 前台（`mac_param_url()`）：题材走 `class`（名字，后端 LIKE），
     * 排序字段走 `by`（`time`/`hits`/`score`/`hits_month`），方向由后端默认 desc。
     */
    override suspend fun filter(
        type: Int,
        page: Int,
        limit: Int,
        area: String,
        year: String,
        lang: String,
        className: String,
        order: String,
    ): AppResult<List<Vod>> =
        apiCall {
            api.listVod(
                type = type,
                page = page,
                limit = limit,
                area = area.trim(),
                year = year.trim(),
                lang = lang.trim(),
                classFilter = className.trim(),
                by = VodOrder.sanitize(order),
            )
        }.flatMap { envelope ->
            envelope.data
                ?.let { pageDto ->
                    saveDefaultFilterPage(type, page, listOf(area, year, lang, className, order), pageDto.list)
                    AppResult.Success(pageDto.list.map { dto -> dto.toDomain() })
                }
                ?: businessFailure(envelope.code, envelope.msg)
        }

    override suspend fun search(keyword: String, limit: Int, typeId: Int?): AppResult<List<Vod>> {
        val client = searchApi ?: return AppResult.Success(emptyList())
        return apiCall { client.search(keyword = keyword, limit = limit, type = typeId) }
            .flatMap { envelope ->
                envelope.data
                    ?.let { pageDto -> AppResult.Success(pageDto.list.map { dto -> dto.toDomain() }) }
                    ?: businessFailure(envelope.code, envelope.msg)
            }
    }

    override suspend fun types(): AppResult<List<VodType>> {
        val client = typeApi ?: return AppResult.Success(emptyList())
        return apiCall { client.vodTypes() }
            .flatMap { envelope ->
                envelope.data
                    ?.let { list ->
                        snapshot?.saveTypes(list.list)
                        filterSnapshot?.saveTypes(list.list)
                        val domain = list.list.map { dto -> dto.toDomain() }
                        typesCache = domain
                        AppResult.Success(domain)
                    }
                    ?: businessFailure(envelope.code, envelope.msg)
            }
    }

    override suspend fun detail(vodId: Int): AppResult<Vod> =
        apiCall { api.vodDetail(vodId = vodId) }
            .flatMap { envelope ->
                envelope.data
                    ?.let { dto ->
                        detailSnapshot?.save(vodId, dto)
                        AppResult.Success(dto.toDomain())
                    }
                    ?: businessFailure(envelope.code, envelope.msg)
            }

    /**
     * 同类型推荐。旧详情页调 `getSameTypeList(type_id, vod_class, page, 3, ...)`，
     * 走的就是 `v1.vod`。
     *
     * 只带 type / page / limit 三个参数：旧页还传 `class=vod_class` 做二级分类收窄，
     * 但新契约只映射了 `type_id` 这一个字段；class 留空时后端按整个类型返回，
     * 语义仍是「同类型」。要收窄时再补 `vod_class` 字段，不要在前端猜。
     */
    override suspend fun recommend(typeId: Int, limit: Int): AppResult<List<Vod>> =
        apiCall { api.listVod(type = typeId, page = RECOMMEND_PAGE, limit = limit) }
            .flatMap { envelope ->
                envelope.data
                    ?.let { pageDto -> AppResult.Success(pageDto.list.map { dto -> dto.toDomain() }) }
                    ?: businessFailure(envelope.code, envelope.msg)
            }
}

/** 推荐只取第 1 页；旧页虽然有翻页变量，但每次都 `setNewData` 覆盖（`:870`）。 */
private const val RECOMMEND_PAGE = 1
