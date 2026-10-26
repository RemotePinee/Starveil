package com.starveil.core.designsystem

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 协议页地址（服务端 `agreement_url` / `privacy_url`）。
 *
 * 客户端不内置协议正文，也不写死域名：两个地址都由后台填，改条款不用发版。
 * 留空时点进去只提示"页面建设中"，而不是打开一个空链接或崩掉。
 */
data class PolicyLinks(
    val agreementUrl: String = "",
    val privacyUrl: String = "",
)

val LocalPolicyLinks = staticCompositionLocalOf { PolicyLinks() }

/**
 * 打开协议页：优先交给系统浏览器/内置浏览器处理。
 *
 * 不内嵌 WebView：协议页是静态文档，用系统浏览器打开不会把登录界面撑出第二种导航形态，
 * 也避免为一个单页再引一套 WebView 配置。
 */
fun openPolicy(context: Context, url: String, name: String) {
    if (url.isBlank()) {
        Toast.makeText(context, "《$name》页面建设中", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (!opened) {
        Toast.makeText(context, "打不开《$name》，请检查后台地址是否有效", Toast.LENGTH_SHORT).show()
    }
}
