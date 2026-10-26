package com.starveil.core.data

import com.starveil.core.network.dto.ApiEnvelopeDto
import com.starveil.core.network.dto.FavoriteDto
import com.starveil.core.network.dto.PageDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登出后内存里的收藏副本必须失效。
 *
 * 只清磁盘不够：`favoriteSnapshotLoaded` 置真后快照只读一次，内存那份会活到进程结束，
 * 于是同一个进程里换账号，收藏页首帧还是上一个账号的列表。
 */
class FavoriteCacheResetTest {

    @Test
    fun `reset local cache stops serving the previous account's favorites`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("favorite-reset").toFile()
        try {
            val repository = DefaultFavoriteRepository(
                api = ListOnlyApi(PageDto(total = 1, limit = 60, list = listOf(favorite(vodId = 101)))),
                snapshot = PageSnapshotStore(SnapshotStore(dir, Dispatchers.Unconfined)),
            )
            repository.list(page = 1)
            assertTrue(repository.cachedFavorites().orEmpty().isNotEmpty())

            // 登出：磁盘清了，但内存副本还在——不显式复位就会一直返回上一个账号的收藏
            dir.deleteRecursively()
            repository.resetLocalCache()

            assertNull(repository.cachedFavorites())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 只关心列表的 fake：其余接口走 [StubStarveilApi] 的"未被调用"兜底。 */
    private class ListOnlyApi(private val page: PageDto<FavoriteDto>) : StubStarveilApi() {
        override suspend fun favoriteList(
            page: Int,
            limit: Int,
            ulogType: Int,
        ): ApiEnvelopeDto<PageDto<FavoriteDto>> = ApiEnvelopeDto(code = 200, data = this.page)
    }

    private fun favorite(vodId: Int): FavoriteDto = FavoriteDto(vodId = vodId, title = "影片 $vodId")
}
