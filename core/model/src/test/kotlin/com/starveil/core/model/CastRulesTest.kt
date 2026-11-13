package com.starveil.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 投屏可用性：判错会让用户投过去黑屏，或者本可投的源被无端禁用。
 * 每条拒绝都要有原因（按钮置灰时直接显示），所以断言到具体分支。
 */
class CastRulesTest {

    private fun source(
        url: String = "https://cdn.example.com/a.m3u8",
        mime: String = "application/vnd.apple.mpegurl",
        needsHeaders: Boolean = false,
        remainingSeconds: Long = 3600,
    ) = CastSource(url, mime, needsHeaders, remainingSeconds)

    @Test
    fun `hls and mp4 direct links are allowed`() {
        assertEquals(CastDecision.Allowed, CastRules.decide(source(), needsClientDecryption = false))
        assertEquals(
            CastDecision.Allowed,
            CastRules.decide(source(url = "https://cdn.example.com/a.mp4", mime = "video/mp4"), false),
        )
    }

    @Test
    fun `encrypted source is rejected with reason`() {
        assertEquals(
            CastDecision.Rejected(CastRules.REASON_ENCRYPTED),
            CastRules.decide(source(), needsClientDecryption = true),
        )
    }

    @Test
    fun `unsupported formats are rejected`() {
        listOf("application/dash+xml", "video/x-flv", "video/x-matroska").forEach { mime ->
            assertEquals(
                CastDecision.Rejected(CastRules.REASON_FORMAT),
                CastRules.decide(source(mime = mime), needsClientDecryption = false),
            )
        }
    }

    @Test
    fun `header dependent source is rejected`() {
        assertEquals(
            CastDecision.Rejected(CastRules.REASON_HEADERS),
            CastRules.decide(source(needsHeaders = true), needsClientDecryption = false),
        )
    }

    @Test
    fun `expiring source is rejected before the tv can fetch it`() {
        assertEquals(
            CastDecision.Rejected(CastRules.REASON_EXPIRED),
            CastRules.decide(
                source(remainingSeconds = CastRules.MIN_REMAINING_SECONDS - 1),
                needsClientDecryption = false,
            ),
        )
    }

    @Test
    fun `non direct urls are rejected`() {
        listOf("file:///data/a.mp4", "/cache/a.mp4", "").forEach { url ->
            assertEquals(
                CastDecision.Rejected(CastRules.REASON_NOT_DIRECT),
                CastRules.decide(source(url = url), needsClientDecryption = false),
            )
        }
    }
}
