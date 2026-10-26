package com.starveil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多皮肤映射。断言表来自 的三个 switch 分支。
 */
class SkinsTest {

    @Test
    fun `server codes map to legacy skin order`() {
        assertEquals(SkinId.YOUKU, Skins.resolve("1").skin)
        assertEquals(SkinId.KATONG, Skins.resolve("2").skin)
        assertEquals(SkinId.BILIBILI, Skins.resolve("3").skin)
        assertEquals(SkinId.BILIBILI2, Skins.resolve("4").skin)
        assertEquals(SkinId.AQC, Skins.resolve("5").skin)
        assertEquals(SkinId.DEFAULT, Skins.resolve("9").skin)
        assertEquals(SkinId.DEFAULT, Skins.resolve(null).skin)
    }

    @Test
    fun `local override wins over server value`() {
        val resolution = Skins.resolve(serverValue = "1", localOverride = "3")

        assertEquals(SkinId.BILIBILI, resolution.skin)
        assertTrue(resolution.fromLocalOverride)
        assertFalse(resolution.contractBroken)
    }

    @Test
    fun `blank local override falls back to server value`() {
        val resolution = Skins.resolve(serverValue = "4", localOverride = "  ")

        assertEquals(SkinId.BILIBILI2, resolution.skin)
        assertFalse(resolution.fromLocalOverride)
    }

    @Test
    fun `missing server value is a contract violation instead of process exit`() {
        // 历史版本在这一步 System.exit(0)
        val resolution = Skins.resolve(serverValue = null)

        assertTrue(resolution.contractBroken)
        assertEquals(SkinId.DEFAULT, resolution.skin)
    }

    @Test
    fun `unknown code is flagged but does not break the contract`() {
        val resolution = Skins.resolve(serverValue = "42")

        assertTrue(resolution.unknownCode)
        assertFalse(resolution.contractBroken)
    }

    @Test
    fun `home variants collapse five skins into three layouts`() {
        assertEquals(HomeVariant.CARD_GRID_B, Skins.home(SkinId.YOUKU))
        assertEquals(HomeVariant.CARD_GRID_B, Skins.home(SkinId.KATONG))
        assertEquals(HomeVariant.CARD_GRID_B, Skins.home(SkinId.AQC))
        assertEquals(HomeVariant.CARD_GRID_D, Skins.home(SkinId.BILIBILI))
        assertEquals(HomeVariant.CARD_GRID_D, Skins.home(SkinId.BILIBILI2))
        assertEquals(HomeVariant.CARD_GRID_C, Skins.home(SkinId.DEFAULT))
    }

    @Test
    fun `rank variants follow the legacy switch`() {
        assertEquals(RankVariant.CARD_B, Skins.rank(SkinId.YOUKU))
        assertEquals(RankVariant.CARD_B, Skins.rank(SkinId.KATONG))
        assertEquals(RankVariant.CARD_B, Skins.rank(SkinId.AQC))
        assertEquals(RankVariant.DEFAULT, Skins.rank(SkinId.BILIBILI))
        assertEquals(RankVariant.DEFAULT, Skins.rank(SkinId.BILIBILI2))
        assertEquals(RankVariant.CARD_C, Skins.rank(SkinId.DEFAULT))
    }

    @Test
    fun `user center keeps one variant per skin`() {
        assertEquals(UserCenterVariant.A, Skins.userCenter(SkinId.DEFAULT))
        assertEquals(UserCenterVariant.B, Skins.userCenter(SkinId.YOUKU))
        assertEquals(UserCenterVariant.C, Skins.userCenter(SkinId.KATONG))
        assertEquals(UserCenterVariant.D, Skins.userCenter(SkinId.BILIBILI))
        assertEquals(UserCenterVariant.E, Skins.userCenter(SkinId.BILIBILI2))
        assertEquals(UserCenterVariant.F, Skins.userCenter(SkinId.AQC))
    }
}
