package com.starveil.app.ads

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.starveil.app.AppContainer
import com.starveil.app.R

/**
 * 广告隐私同意入口。
 *
 * 单独成文件而不是写进 `StarveilNavHost`：那个 composable 的圈复杂度已经贴着
 * detekt 的阈值（10），再加一个分支就会让 `./gradlew check` 直接红。
 *
 * 语义：只在没问过时弹一次；同意 → 记录并立即重建网关（SDK 生效），
 * 暂不 → 只记录，网关保持直投（不采集设备信息）。
 *
 * 文案与政策地址都来自启动配置，不写死：`text` 为空时用本地默认串，
 * `privacyUrl` 为空时不显示"查看隐私政策"入口。
 */
@Composable
internal fun AdConsentGate(
    container: AppContainer,
    context: Context,
    text: String,
    privacyUrl: String,
) {
    // 开屏还在时先不弹：Dialog 是独立 window，会直接压在开屏图上。
    val splashActive = LocalSplashActive.current
    var prompt by remember { mutableStateOf(AdConsentStore.needsPrompt(context)) }
    if (!prompt || splashActive) return
    val fallback = stringResource(R.string.ad_consent_body)
    AdConsentDialog(
        body = text.ifBlank { fallback },
        onOpenPolicy = privacyUrl.takeIf { it.isNotBlank() }?.let { url -> { context.openPolicy(url) } },
        onAgree = {
            AdConsentStore.grant(context)
            prompt = false
            container.refreshAdGateway()
        },
        onDecline = {
            AdConsentStore.decline(context)
            prompt = false
            container.refreshAdGateway()
        },
    )
}

/** 只放行 http/https：后台把地址填错也不该变成可执行入口。 */
private fun Context.openPolicy(url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "http" && uri.scheme != "https") return
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // 设备没有浏览器：隐私页打不开不影响"同意/暂不"这一步
    }
}
