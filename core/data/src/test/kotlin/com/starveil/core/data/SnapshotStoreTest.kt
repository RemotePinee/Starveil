package com.starveil.core.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 快照时效：读侧必须能自己判过期。
 *
 * 后台删内容时客户端拿不到任何"删除信号"。如果快照只靠"下一次同参数请求成功"失效，
 * 请求失败或用户不再刷新时，已删内容会一直留在首帧里——直播页那组已删频道就是这么来的。
 * 锁住的就是那条兜底：文件还在，但老了就不算数。
 */
class SnapshotStoreTest {

    @Test
    fun `expired snapshot is ignored even though the file is still there`() {
        withTempStore { store, dir ->
            store.write(NAME, String.serializer(), "旧内容")
            assertEquals("旧内容", store.read(NAME, String.serializer()))

            // 把写入时间往前拨两天：文件还在，但已经超过默认寿命
            val file = dir.listFiles().orEmpty().single()
            file.setLastModified(System.currentTimeMillis() - TWO_DAYS_MS)

            assertNull(store.read(NAME, String.serializer()))
        }
    }

    @Test
    fun `keep forever snapshot survives any age`() {
        withTempStore { store, dir ->
            store.write(NAME, String.serializer(), "我的收藏")
            val file = dir.listFiles().orEmpty().single()
            file.setLastModified(System.currentTimeMillis() - TEN_YEARS_MS)

            assertEquals(
                "我的收藏",
                store.read(NAME, String.serializer(), SnapshotStore.KEEP_FOREVER),
            )
        }
    }

    /** 写成 `Dispatchers.Unconfined` 后落盘是同步的，不用 sleep 去等异步写。 */
    private fun withTempStore(block: (SnapshotStore, File) -> Unit) {
        val dir = Files.createTempDirectory("snapshot-ttl").toFile()
        try {
            block(SnapshotStore(dir, Dispatchers.Unconfined), dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val NAME = "ttl_probe"

        const val TWO_DAYS_MS = 48L * 60 * 60 * 1000

        const val TEN_YEARS_MS = 3650L * 24 * 60 * 60 * 1000
    }
}
