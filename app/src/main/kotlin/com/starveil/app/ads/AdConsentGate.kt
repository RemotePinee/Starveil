package com.starveil.app.ads

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starveil.app.AppContainer
import com.starveil.app.R
import com.starveil.app.dialog.AppDialogKind
import com.starveil.app.dialog.DialogRequest
import com.starveil.core.model.StartupConfig

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
    if (!prompt) return

    val request = remember { DialogRequest(AppDialogKind.Consent, CONSENT_KEY) }
    val scheduler = container.dialogScheduler
    val current by scheduler.current.collectAsStateWithLifecycle()

    // 开屏退场后才入队：让调度器决定"现在是不是轮到我"，而不是自己抢窗口
    LaunchedEffect(splashActive) {
        if (!splashActive) scheduler.offer(request)
    }
    if (splashActive || current?.key != request.key) return

    val fallback = stringResource(R.string.ad_consent_body)
    AdConsentDialog(
        body = text.ifBlank { fallback },
        onOpenPolicy = privacyUrl.takeIf { it.isNotBlank() }?.let { url -> { context.openPolicy(url) } },
        onAgree = {
            AdConsentStore.grant(context)
            scheduler.complete(request)
            prompt = false
            container.refreshAdGateway()
        },
    )
}

/** 隐私同意的频控 key：全局唯一，标记过一次就永不再弹。 */
private const val CONSENT_KEY = "consent"

/**
 * 启动配置到位后才组合 [AdConsentGate]。
 *
 * 单独一层是为了不在 `StarveilNavHost` 里再加分支——那个 composable
 * 的圈复杂度已经贴着 detekt 阈值。配置没到时文案与政策地址都是空的，
 * 弹出来也只剩一个没有出处的协议正文。
 */
@Composable
internal fun AdConsentGateWhenReady(
    container: AppContainer,
    context: Context,
    text: String,
    startup: StartupConfig?,
) {
    if (startup == null) return
    AdConsentGate(
        container = container,
        context = context,
        text = text,
        privacyUrl = startup.privacyUrl.orEmpty(),
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
