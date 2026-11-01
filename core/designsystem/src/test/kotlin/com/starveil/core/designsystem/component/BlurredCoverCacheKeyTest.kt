package com.starveil.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BlurredCoverCacheKeyTest {
    /**
     * 预取与同步读各算一次键，两次必须完全一致：差一个字符，预取就白解，
     * 切剧那一帧又只能落打底色。
     */
    @Test
    fun `key is stable for the same url`() {
        assertEquals(blurredCoverCacheKey(COVER), blurredCoverCacheKey(COVER))
    }

    /** 键里必须带变换自己的 cacheKey：改半径或 σ 时旧位图自动失效，不会画出旧参数的糊图。 */
    @Test
    fun `key carries the blur transformation version`() {
        val blur = ImageBlurTransformation().cacheKey
        assertEquals("blurredCover:$blur:$COVER", blurredCoverCacheKey(COVER))
    }

    /** 不同封面必须是不同键，否则切剧会命中上一部剧的模糊图。 */
    @Test
    fun `key differs per cover`() {
        assertNotEquals(blurredCoverCacheKey(COVER), blurredCoverCacheKey("$COVER?v=2"))
    }

    private companion object {
        const val COVER = "https://img.example.com/cover/1024.jpg"
    }
}
