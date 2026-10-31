package com.starveil.core.data

import com.starveil.core.model.HomeBanner
import com.starveil.core.model.HomeBlock
import com.starveil.core.model.HomeLink
import com.starveil.core.model.HomeSpotlight
import com.starveil.core.model.HomeVariant
import com.starveil.core.model.Vod
import com.starveil.core.model.VodType
import com.starveil.core.network.dto.HomeBannerDto
import com.starveil.core.network.dto.HomeBlockDto
import com.starveil.core.network.dto.HomeLinkDto
import com.starveil.core.network.dto.HomeSpotlightDto
import com.starveil.core.network.dto.VodDto
import com.starveil.core.network.dto.VodTypeDto
import com.starveil.core.network.mapper.toDomain
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * 首页首帧快照：把上一次成功的各区块响应拼成一份，冷启动时直接出完整首页。
 *
 * 为什么不是「等全部接口回来再显示」：首页串行发十来个请求，第一个字节到最后一
 * 个字节之间用户只能看转圈。有了这份快照，冷启动第一帧就是上次的样子，
 * 网络回来后原地覆盖——这也是主流视频 App 的做法（stale-while-revalidate）。
 */
@Serializable
internal data class HomeSnapshotDto(
    val items: List<VodDto> = emptyList(),
    val banners: List<HomeBannerDto> = emptyList(),
    val blocks: List<HomeBlockDto> = emptyList(),
    val links: List<HomeLinkDto> = emptyList(),
    val featured: HomeSpotlightDto? = null,
    val hot: HomeSpotlightDto? = null,
    val guess: HomeSpotlightDto? = null,
    val types: List<VodTypeDto> = emptyList(),
    /** 首页版式与列数：不存的话首帧会用默认皮肤，配置回来再跳一下。 */
    val variant: String = "",
    val columns: Int = 0,
)

/**
 * 首页快照的读写口。写入发生在 [DefaultVodRepository] 每个接口成功之后，
 * 读取发生在 [com.starveil.feature.home.HomeViewModel] 的构造期（首帧）。
 *
 * 内存里始终留一份，避免每写一个区块就整份读盘一次；仅当内容变化时才落盘。
 *
 * **懒加载**：本类在 `AppContainer`（即 `Application.onCreate`）里构造，若属性初始化就
 * `read()`，冷启动首帧之前要做一次读文件 + JSON 反序列化。改成第一次真正取内容时才读，
 * 与 [DetailSnapshotStore] 同一种口径。
 */
class HomeSnapshotStore(private val store: SnapshotStore) {

    /**
     * 进程内副本；null = 还没读过盘。
     *
     * `@Volatile`：[saveXxx] 在协程（IO 线程）里写，[items] 等在首帧的主线程裸读。
     * 写侧的 `@Synchronized` 只保证写互斥，不建立读的 happens-before。
     */
    @Volatile
    private var dto: HomeSnapshotDto? = null

    /** 有列表才算"有可渲染的首帧"，只有轮播/分类不算。 */
    fun hasContent(): Boolean = loaded().items.isNotEmpty()

    fun items(): List<Vod> = loaded().items.map { it.toDomain() }

    fun banners(): List<HomeBanner> = loaded().banners.map { it.toDomain() }

    fun blocks(): List<HomeBlock> = loaded().blocks.map { it.toDomain() }

    fun links(): List<HomeLink> = loaded().links.map { it.toDomain() }

    fun featured(): HomeSpotlight? = loaded().featured?.toDomain()

    fun hot(): HomeSpotlight? = loaded().hot?.toDomain()

    fun guess(): HomeSpotlight? = loaded().guess?.toDomain()

    /**
     * 分类行只给一级分类。
     *
     * 快照存的是接口原样（含二级），在读取时过滤而不是写入时过滤：老快照里已经混进二级的
     * 数据也要能修好，否则用户升级后顶部 Tab 还是十几个频道，必须清缓存才正常。
     */
    fun types(): List<VodType> = loaded().types.map { it.toDomain() }.filter { it.parentId == 0 }

    /** 上次用的首页版式；没存过或枚举已改名都返回 null，由调用方退回默认皮肤。 */
    fun variant(): HomeVariant? = loaded().variant
        .takeIf { it.isNotBlank() }
        ?.let { name -> runCatching { HomeVariant.valueOf(name) }.getOrNull() }

    fun columns(): Int = loaded().columns

    @Synchronized
    fun saveLayout(variant: HomeVariant, columns: Int) =
        update(loaded().copy(variant = variant.name, columns = columns))

    @Synchronized
    fun saveItems(list: List<VodDto>) = update(loaded().copy(items = list))

    @Synchronized
    fun saveBanners(list: List<HomeBannerDto>) = update(loaded().copy(banners = list))

    @Synchronized
    fun saveBlocks(list: List<HomeBlockDto>) = update(loaded().copy(blocks = list))

    @Synchronized
    fun saveLinks(list: List<HomeLinkDto>) = update(loaded().copy(links = list))

    @Synchronized
    fun saveFeatured(spotlight: HomeSpotlightDto) = update(loaded().copy(featured = spotlight))

    @Synchronized
    fun saveHot(spotlight: HomeSpotlightDto) = update(loaded().copy(hot = spotlight))

    @Synchronized
    fun saveGuess(spotlight: HomeSpotlightDto) = update(loaded().copy(guess = spotlight))

    @Synchronized
    fun saveTypes(list: List<VodTypeDto>) = update(loaded().copy(types = list))

    /** 取内存副本；没有才读盘，且只读一次（双击检查，重入 `@Synchronized` 无碍）。 */
    private fun loaded(): HomeSnapshotDto = dto ?: readFromDisk()

    @Synchronized
    private fun readFromDisk(): HomeSnapshotDto {
        dto?.let { return it }
        val restored = store.read(NAME, serializer<HomeSnapshotDto>()) ?: HomeSnapshotDto()
        dto = restored
        return restored
    }

    private fun update(next: HomeSnapshotDto) {
        if (next == loaded()) return
        dto = next
        store.write(NAME, serializer<HomeSnapshotDto>(), next)
    }

    private companion object {
        const val NAME = "home_feed"
    }
}
