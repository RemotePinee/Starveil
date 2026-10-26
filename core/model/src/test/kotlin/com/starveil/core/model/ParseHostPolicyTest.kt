package com.starveil.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析页准入策略。边界对齐 docs/security.md 第 5 节：只允许 https、拒绝内网。
 */
class ParseHostPolicyTest {

    @Test
    fun `https origin is allowed`() {
        assertTrue(ParseHostPolicy.permits("https://api.example.com/parse?url=x"))
        assertTrue(ParseHostPolicy.permits("https://api.example.com:8443/parse?url=x"))
    }

    @Test
    fun `plain http is rejected`() {
        assertFalse(ParseHostPolicy.permits("http://api.example.com/parse?url=x"))
    }

    @Test
    fun `loopback and private ranges are rejected`() {
        assertFalse(ParseHostPolicy.permits("https://localhost/parse"))
        assertFalse(ParseHostPolicy.permits("https://127.0.0.1/parse"))
        assertFalse(ParseHostPolicy.permits("https://10.1.2.3/parse"))
        assertFalse(ParseHostPolicy.permits("https://192.168.1.10/parse"))
        assertFalse(ParseHostPolicy.permits("https://172.16.5.5/parse"))
        assertFalse(ParseHostPolicy.permits("https://169.254.169.254/latest/meta-data"))
        assertFalse(ParseHostPolicy.permits("https://[::1]/parse"))
    }

    @Test
    fun `public ip is allowed`() {
        assertTrue(ParseHostPolicy.permits("https://203.0.113.10/parse"))
        assertTrue(ParseHostPolicy.permits("https://172.32.0.1/parse"))
    }

    @Test
    fun `user info does not smuggle a private host`() {
        assertFalse(ParseHostPolicy.permits("https://user@127.0.0.1/parse"))
        assertFalse(ParseHostPolicy.permits("https://user:pass@10.0.0.1/parse"))
    }

    @Test
    fun `blank and malformed input is rejected`() {
        assertFalse(ParseHostPolicy.permits(null))
        assertFalse(ParseHostPolicy.permits(""))
        assertFalse(ParseHostPolicy.permits("   "))
        assertFalse(ParseHostPolicy.permits("api.example.com/parse"))
        assertFalse(ParseHostPolicy.permits("https:///parse"))
    }
}
