package com.starveil.core.data

import com.starveil.core.common.AppError
import com.starveil.core.common.AppResult
import com.starveil.core.model.PlaySource
import com.starveil.core.model.PlayUrl
import com.starveil.core.model.PlayerInfo
import com.starveil.core.model.Vod
import com.starveil.core.model.VodType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 短剧仓库。短剧只是 `v1.vod` 的一个分类，所以这里守三件事：
 * 分类号取后台配置、没配才回落常量、`Vod` → `ShortPlay` 的字段映射不能猜。
 */
class DefaultShortPlayRepositoryTest {

    @Test
    fun `feed requests short play type and maps vod fields`() {
        runTest {
            val vodRepository = FakeVodRepository(
                result = AppResult.Success(
                    listOf(
                        vod(
                            id = 7,
                            name = "总裁的替身新娘",
                            poster = "https://img.example.com/7.jpg",
                            blurb = "她替姐姐嫁入豪门。",
                            episodeCounts = intArrayOf(80),
                        ),
                    ),
                ),
            )

            // 命名参数：feed 的首个位置参数是 typeId，位置传参会把分页数当成分类号
            val feedResult = DefaultShortPlayRepository(vodRepository).feed(page = 2, limit = 10)
            val shortPlays = (feedResult as AppResult.Success).value

            assertEquals(FALLBACK_SHORT_PLAY_TYPE_ID, vodRepository.requestedType)
            assertEquals(2, vodRepository.requestedPage)
            assertEquals(10, vodRepository.requestedLimit)

            assertEquals(1, shortPlays.size)
            val shortPlay = shortPlays.first()
            assertEquals(7, shortPlay.id)
            assertEquals("总裁的替身新娘", shortPlay.title)
            assertEquals("https://img.example.com/7.jpg", shortPlay.cover)
            assertEquals(80, shortPlay.episodeCount)
            assertEquals(0, shortPlay.freeEpisodes)
            assertNull(shortPlay.category)
            assertEquals("她替姐姐嫁入豪门。", shortPlay.blurb)
        }
    }

    @Test
    fun `search stays inside the configured type and passes keyword through`() = runTest {
        val vodRepository = FakeVodRepository(
            result = AppResult.Success(listOf(vod(id = 31, name = "总裁的替身新娘"))),
        )

        val result = DefaultShortPlayRepository(vodRepository) { 11 }.search("总裁", limit = 5)
        val shortPlays = (result as AppResult.Success).value

        // 关键词原样透给后端，分类仍是后台配的那个——搜索结果不能漏成全站
        assertEquals("总裁", vodRepository.requestedKeyword)
        assertEquals(11, vodRepository.requestedSearchType)
        assertEquals(5, vodRepository.requestedSearchLimit)
        assertEquals("总裁的替身新娘", shortPlays.single().title)
    }

    @Test
    fun `configured type id wins over fallback`() = runTest {
        val vodRepository = FakeVodRepository(result = AppResult.Success(emptyList()))

        DefaultShortPlayRepository(vodRepository) { 11 }.feed()

        // 后台填的分类号必须生效：换站不该再改代码发版
        assertEquals(11, vodRepository.requestedType)
    }

    @Test
    fun `unset type id falls back instead of requesting every vod`() = runTest {
        val vodRepository = FakeVodRepository(result = AppResult.Success(emptyList()))

        DefaultShortPlayRepository(vodRepository) { 0 }.feed()

        // 0 在后端等于「不限分类」，透下去剧场页会变成全站影片混排
        assertEquals(FALLBACK_SHORT_PLAY_TYPE_ID, vodRepository.requestedType)
    }

    @Test
    fun `category comes from vod type name and blank counts as none`() {
        runTest {
            val vodRepository = FakeVodRepository(
                result = AppResult.Success(listOf(vod(id = 21, typeName = "都市"), vod(id = 22, typeName = "  "))),
            )

            val shortPlays = (DefaultShortPlayRepository(vodRepository).feed() as AppResult.Success).value

            assertEquals("都市", shortPlays[0].category)
            // 后端不下发 type_name，或分类名是空白：都不能生成一个叫"  "的 chip
            assertNull(shortPlays[1].category)
        }
    }

    @Test
    fun `empty page stays success with no items`() {
        runTest {
            val vodRepository = FakeVodRepository(result = AppResult.Success(emptyList()))

            val result = DefaultShortPlayRepository(vodRepository).feed()

            assertTrue(result is AppResult.Success)
            assertTrue((result as AppResult.Success).value.isEmpty())
        }
    }

    @Test
    fun `failure is propagated unchanged`() {
        runTest {
            val failure = AppResult.Failure(AppError.Business(code = 500, message = "服务端错误"))
            val vodRepository = FakeVodRepository(result = failure)

            val result = DefaultShortPlayRepository(vodRepository).feed()

            assertEquals(failure, result)
        }
    }

    @Test
    fun `episode count comes from the first play source only`() {
        runTest {
            val vodRepository = FakeVodRepository(
                result = AppResult.Success(listOf(vod(id = 11, episodeCounts = intArrayOf(12, 3)))),
            )

            val shortPlay = (DefaultShortPlayRepository(vodRepository).feed() as AppResult.Success).value.first()

            assertEquals(12, shortPlay.episodeCount)
        }
    }

    @Test
    fun `vod without play sources falls back to zero episodes`() {
        runTest {
            val vodRepository = FakeVodRepository(
                result = AppResult.Success(listOf(vod(id = 13, poster = null, episodeCounts = intArrayOf()))),
            )

            val shortPlay = (DefaultShortPlayRepository(vodRepository).feed() as AppResult.Success).value.first()

            assertEquals(0, shortPlay.episodeCount)
            assertNull(shortPlay.cover)
            assertNull(shortPlay.category)
        }
    }
}

/** 只实现本仓库用到的方法；其余沿用 [VodRepository] 的默认实现。 */
private class FakeVodRepository(private val result: AppResult<List<Vod>>) : VodRepository {

    var requestedType: Int? = null
    var requestedPage: Int? = null
    var requestedLimit: Int? = null
    var requestedKeyword: String? = null
    var requestedSearchType: Int? = null
    var requestedSearchLimit: Int? = null

    override suspend fun list(type: Int, page: Int, limit: Int): AppResult<List<Vod>> {
        requestedType = type
        requestedPage = page
        requestedLimit = limit
        return result
    }

    override suspend fun search(keyword: String, limit: Int, typeId: Int?): AppResult<List<Vod>> {
        requestedKeyword = keyword
        requestedSearchLimit = limit
        requestedSearchType = typeId
        return result
    }

    override suspend fun detail(vodId: Int): AppResult<Vod> {
        val vods = (result as? AppResult.Success)?.value.orEmpty()
        return vods.firstOrNull { it.id == vodId }
            ?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.Business(code = 404, message = "未找到"))
    }

    override suspend fun types(): AppResult<List<VodType>> = AppResult.Success(emptyList())
}

private fun vod(
    id: Int,
    name: String = "短剧 $id",
    poster: String? = "https://img.example.com/$id.jpg",
    blurb: String? = null,
    typeName: String? = null,
    episodeCounts: IntArray = intArrayOf(1),
): Vod = Vod(
    id = id,
    name = name,
    poster = poster,
    remarks = "更新至 ${episodeCounts.firstOrNull() ?: 0} 集",
    year = null,
    area = null,
    score = null,
    blurb = blurb,
    playSources = episodeCounts.mapIndexed { index, count -> playSource(index, count) },
    typeName = typeName,
)

private fun playSource(index: Int, episodeCount: Int): PlaySource = PlaySource(
    index = index,
    name = "源 $index",
    episodes = (1..episodeCount).map { number ->
        PlayUrl(episodeNumber = number, title = "第 $number 集", url = "https://cdn.example.com/$index/$number.m3u8")
    },
    playerInfo = PlayerInfo(
        parseTargets = emptyList(),
        directLinkPatterns = emptyList(),
        headerPatterns = emptyList(),
        headers = emptyMap(),
    ),
)
