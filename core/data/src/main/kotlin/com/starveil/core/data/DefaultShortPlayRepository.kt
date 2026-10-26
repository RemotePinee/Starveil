package com.starveil.core.data

import com.starveil.core.common.AppResult
import com.starveil.core.common.map
import com.starveil.core.model.ShortPlay
import com.starveil.core.model.Vod
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * 短剧仓库。短剧没有独立接口：它就是 `v1.vod` 里某个 `type_id` 的那一批，
 * 所以这里只做"影片 → 短剧"的字段映射与原样转发，网络路径常量一个都不新增。
 *
 * 分类号由启动配置 `shortplay_type_id` 下发（后台「基础」页维护）。macCMS 的
 * `type_id` 是站点自增的，联调站是 30、另一个站可能是 11，写死在代码里就会变成
 * "每换一个站都得改代码发版"。只有后台没配时才回落到 [FALLBACK_SHORT_PLAY_TYPE_ID]。
 *
 * 注意 `Vod.name` 在短剧场景下是剧名而不是文件名，`remarks`（"更新至 80 集"）不参与映射：
 * 集数以播放源为准，文案不作为数据。
 */
class DefaultShortPlayRepository(
    private val vodRepository: VodRepository,
    /**
     * 首帧快照；为 null 时不落盘（测试与其它入口构造本类时不必提供）。
     *
     * 必须排在 [configuredTypeId] **之前**：测试里用尾随 lambda 传分类号
     * （`DefaultShortPlayRepository(vodRepository) { 11 }`），末位参数得留成函数类型。
     */
    private val snapshot: PageSnapshotStore? = null,
    private val configuredTypeId: () -> Int = { 0 },
) : ShortPlayRepository {

    override fun cachedShortPlay(typeId: Int?): List<ShortPlay>? =
        snapshot?.read(keyOf(typeId), serializer<List<ShortPlayCacheDto>>())
            ?.map { play -> play.toDomain() }
            ?.takeIf { plays -> plays.isNotEmpty() }

    override suspend fun feed(typeId: Int?, page: Int, limit: Int): AppResult<List<ShortPlay>> {
        val resolved = resolveTypeId(typeId)
        return vodRepository.list(type = resolved, page = page, limit = limit)
            .map { vods ->
                val plays = vods.map { vod -> vod.toShortPlay() }
                saveFirstPage(resolved, plays, page = page)
                plays
            }
    }

    /** 每个分类单独存一份首帧快照：切回某个分类时能立刻铺出它上次的内容。 */
    private fun keyOf(typeId: Int?): String =
        "${PageSnapshotStore.KEY_SHORT_PLAY}_${resolveTypeId(typeId)}"

    /**
     * 只存第一页：翻页拿到的只是同一份列表的续集，单独存也铺不出完整首屏。
     * 写入非 suspend，[SnapshotStore] 内部切 IO 线程。
     */
    private fun saveFirstPage(typeId: Int, plays: List<ShortPlay>, page: Int) {
        if (page != 1) return
        // 这个分类被清空了：删掉旧快照，否则切回来还是上一次的海报墙
        if (plays.isEmpty()) {
            snapshot?.remove(keyOf(typeId))
            return
        }
        snapshot?.write(
            keyOf(typeId),
            serializer<List<ShortPlayCacheDto>>(),
            plays.toCache(),
        )
    }

    /**
     * 后台配置优先，0 或负数视为没配。
     *
     * 不能把 0 原样透给后端：`Vods::filteredQuery()` 只在 `type > 0` 时加条件，
     * `type=0` 的含义是"不限分类"，剧场页会退化成全站影片混排。
     */
    private fun resolveTypeId(typeId: Int?): Int =
        (typeId ?: configuredTypeId()).takeIf { it > 0 } ?: FALLBACK_SHORT_PLAY_TYPE_ID
}

/**
 * 后台没配 `shortplay_type_id` 时的兜底分类（联调站约定，计划 §2.1）。
 * 只作兜底，正常路径由启动配置下发。
 */
const val FALLBACK_SHORT_PLAY_TYPE_ID: Int = 30

/**
 * 播放源是"源 → 集"两层：短剧约定单源，多源时只认第一个源，
 * 求和会把同一部剧重复计入。
 */
private fun Vod.toShortPlay(): ShortPlay = ShortPlay(
    id = id,
    title = name,
    cover = poster,
    episodeCount = playSources.firstOrNull()?.episodes?.size ?: 0,
    // 服务端暂未下发"前 N 集免费"，先固定 0；可看性由 TrySeeRules 判定，不要据此推断全付费。
    freeEpisodes = 0,
    // 分类名来自插件按 type_id 批量查的 mac_type.type_name；后端不下发，空白一律当没有，
    // 不拿 remarks 顶替（那是猜，不是映射）。
    category = typeName?.takeIf { it.isNotBlank() },
    blurb = blurb,
)

/**
 * 剧场页快照的落盘结构。
 *
 * 拿不到 `VodDto`：[feed] 走 [VodRepository.list]，得到的已经是映射后的 [Vod]，
 * 原始 DTO 在 `DefaultVodRepository` 内部就丢了。于是按卡片需要的字段单独存一份；
 * 字段全部带默认值，后端加字段不会让旧快照读不出来（同 [SnapshotStore] 的约定）。
 * 只存领域字段、不存播放源：剧场页卡片只要这七项，存多了等于把无效数据写进磁盘。
 */
@Serializable
private data class ShortPlayCacheDto(
    val id: Int = 0,
    val title: String = "",
    val cover: String? = null,
    val episodeCount: Int = 0,
    val freeEpisodes: Int = 0,
    val category: String? = null,
    val blurb: String? = null,
)

private fun List<ShortPlay>.toCache(): List<ShortPlayCacheDto> = map { play ->
    ShortPlayCacheDto(
        id = play.id,
        title = play.title,
        cover = play.cover,
        episodeCount = play.episodeCount,
        freeEpisodes = play.freeEpisodes,
        category = play.category,
        blurb = play.blurb,
    )
}

private fun ShortPlayCacheDto.toDomain(): ShortPlay = ShortPlay(
    id = id,
    title = title,
    cover = cover,
    episodeCount = episodeCount,
    freeEpisodes = freeEpisodes,
    category = category,
    blurb = blurb,
)
