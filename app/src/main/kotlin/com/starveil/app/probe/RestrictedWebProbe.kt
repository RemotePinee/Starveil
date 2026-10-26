package com.starveil.app.probe

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.starveil.core.model.ParseHostPolicy
import com.starveil.core.model.ParseRules
import com.starveil.core.model.PlayStream
import com.starveil.core.model.PlaybackMachine
import com.starveil.core.network.ApiClient
import com.starveil.feature.player.LineProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 线路探测：先走纯 HTTP，拿不到再用**受限 WebView** 兜底。
 *
 * 只有一个不受限的 WebView：证书错误放行、
 * 允许 file 访问、允许页面自开窗口、允许混合内容。这里逐条反转，理由见
 * docs/security.md 第 5 节：
 *
 * | 常见配置 | 本实现 |
 * |---|---|
 * | 证书错误放行 | `handler.cancel()`，证书错就放弃该线路 |
 * | 允许 file 访问 | `allowFileAccess = false` / `allowContentAccess = false` |
 * | 混合内容总是放行 | `MIXED_CONTENT_NEVER_ALLOW` |
 * | 允许 JS 开窗口 / 多窗口 | 全部关闭 |
 * | 定位开启 | 关闭 |
 * | 广告 WebView 的 JS 桥 | 不注入任何 JS 接口 |
 *
 * 另外：解析页必须是 https 且非内网（[ParseHostPolicy]），嗅探到的资源地址同样过一遍；
 * WebView 仅在主线程创建，完成或超时后立即 destroy，不常驻、不复用。
 */
class RestrictedWebProbe(
    private val context: Context,
    private val apiClient: ApiClient,
) : LineProbe {

    override suspend fun probe(targetIndex: Int, target: String, vodUrl: String): PlayStream? {
        val requestUrl = apiClient.signedParseUrl(target, vodUrl)
        return when {
            // 域名不合格直接放弃该线路，绝不把不可信地址交给 WebView
            !ParseHostPolicy.permits(requestUrl) -> null
            // JSON 模板（含 "json" 或 ".."）直接走 HTTP，不必起 WebView
            // JSON 模板能拿到 type/audio，双流只在这条路径上支持
            ParseRules.usesJsonEndpoint(requestUrl) ->
                withContext(Dispatchers.IO) { apiClient.probePlayUrl(target, vodUrl) }

            // WebView 嗅探只能拿到单条地址，没有音轨字段
            else -> sniffWithRestrictedWebView(requestUrl)?.let { ParseRules.normalizeStream(it) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffWithRestrictedWebView(requestUrl: String): String? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                var webView: WebView? = null

                fun finish(result: String?) {
                    if (continuation.isActive) continuation.resume(result)
                    webView?.let { view ->
                        view.stopLoading()
                        view.destroy()
                    }
                    webView = null
                    handler.removeCallbacksAndMessages(null)
                }

                val timeout = Runnable { finish(null) }

                val client = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val candidate = request.url?.toString().orEmpty()
                        if (ParseHostPolicy.permits(candidate) && ParseRules.looksLikePlayUrl(candidate)) {
                            finish(candidate)
                        }
                        return null
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = !ParseHostPolicy.permits(request.url?.toString())

                    override fun onReceivedSslError(
                        view: WebView,
                        sslErrorHandler: SslErrorHandler,
                        error: SslError,
                    ) {
                        // 直接放行证书错误：等于关闭该域名的校验
                        sslErrorHandler.cancel()
                        finish(null)
                    }
                }

                val view = WebView(context)
                webView = view
                view.webViewClient = client
                view.settings.applyRestrictedDefaults()
                // 任何异常都不该把播放页带崩：加载失败交给超时兜底
                runCatching { view.loadUrl(requestUrl) }
                handler.postDelayed(timeout, PlaybackMachine.LINE_TIMEOUT_MS)

                continuation.invokeOnCancellation {
                    handler.removeCallbacksAndMessages(null)
                    handler.post {
                        webView?.stopLoading()
                        webView?.destroy()
                        webView = null
                    }
                }
            }
        }
}

/**
 * WebView 的受限配置。集中在一处，避免以后有人"顺手"把旧开关加回来。
 *
 * 注意：`javaScriptEnabled` 是必须开的——解析站靠 JS 发流请求，关掉就嗅不到地址；
 * 正因如此，其它能放大风险的开关（文件访问、多窗口、混合内容、证书）必须全部收紧。
 */
private fun WebSettings.applyRestrictedDefaults() {
    javaScriptEnabled = true // security-baseline-allow: 解析站靠 JS 发流请求，见上方说明
    allowFileAccess = false
    allowContentAccess = false
    domStorageEnabled = false
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)
    setGeolocationEnabled(false)
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    cacheMode = WebSettings.LOAD_NO_CACHE
    mediaPlaybackRequiresUserGesture = true
    blockNetworkImage = true
}
