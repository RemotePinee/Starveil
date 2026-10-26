package com.starveil.app.ads

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.starveil.core.common.ad.AdPlacement
import com.starveil.core.common.ad.AdPlacements
import com.starveil.core.common.ad.AdSlot
import com.starveil.core.designsystem.component.AdBadge
import com.starveil.core.designsystem.component.LocalAdClick
import com.starveil.core.designsystem.component.LocalAdPlacements
import com.starveil.core.designsystem.component.LocalRewardAd
import com.starveil.core.designsystem.starveilExtraColors
import com.starveil.core.designsystem.token.Spacing
import kotlinx.coroutines.delay

/** 开屏倒计时（秒）。5 秒后自动进主界面。 */
private const val SPLASH_SECONDS = 5

/** 跳过按钮的深色底透明度：压在图上也看得清，又不至于盖住画面。 */
private const val SKIP_BG_ALPHA = 0.55f

/**
 * 广告宿主：把五个广告位的配置、落地页打开方式、激励入口一次性提供下去。
 *
 * 放在 NavHost 外层而不是每个页面各接一次——开屏与激励是**全屏层**，
 * 和导航无关；列表/播放器里的位只需要读 CompositionLocal 就能拿到配置。
 *
 * 激励位的出口顺序（视频 → 图片 → 聚合 SDK → 不弹）在 [RewardHost] 里，
 * 浮层挂载在 [RewardOverlay] 里；本函数只负责提供配置与挂两个全屏层。
 *
 * 开屏位不在这里走 SDK：TopOn / TradPlus 的开屏都要接管宿主的 contentView，
 * 单 Activity + Compose 的结构下会把界面一起掀掉；要接只能给它一个独立的
 * SplashActivity。所以开屏只用直投素材，SDK 开屏位仍由后台配置留着备用。
 */
@Composable
internal fun AdHost(
    ads: AdPlacements,
    context: Context,
    runtime: AdRuntime,
    content: @Composable () -> Unit,
) {
    val reward = rememberRewardHost(ads = ads, runtime = runtime)
    val startReward: (() -> Unit) -> Unit = remember(reward) { reward::start }
    val openLanding: (String) -> Unit = remember(context) { { url -> context.openLanding(url) } }
    // 只预加载会被展示的全屏位：开屏不走 SDK（见上面的说明），横幅/信息流归页面画
    LaunchedEffect(ads.platform) {
        listOf(AdSlot.REWARD, AdSlot.PAUSE).forEach(runtime.gateway::preload)
    }

    // 开屏是否还盖着：隐私弹窗要等它退场再弹，否则 Dialog 是独立 window，
    // 会直接压在开屏图上，用户先看到协议、后看到广告。
    var splashDone by rememberSaveable { mutableStateOf(false) }
    val splashActive = !splashDone && ads.splash.drawable

    CompositionLocalProvider(
        LocalAdPlacements provides ads,
        LocalAdClick provides openLanding,
        LocalRewardAd provides startReward,
        LocalSplashActive provides splashActive,
    ) {
        // 全屏层必须在 Provider 内部：开屏与激励都要用同一个落地页打开方式，
        // 放到外面会读到默认的空实现，点击静默失效。
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            SplashGate(ads = ads, shown = splashDone, onShown = { splashDone = true })
            RewardOverlay(host = reward, creative = ads.reward, repository = runtime.unlock)
        }
    }
}

/** 开屏还盖在界面上时为 true；隐私弹窗读它来推迟展示。 */
internal val LocalSplashActive = compositionLocalOf { false }

/**
 * 开屏：只在首次组合时出现一次。
 *
 * 用 `rememberSaveable` 记"已展示"：旋转屏幕不该再看一遍广告；进程重启再看一次。
 * 状态提到 [AdHost]，因为隐私弹窗要等它退场。
 */
@Composable
private fun SplashGate(ads: AdPlacements, shown: Boolean, onShown: () -> Unit) {
    val creative = ads.splash
    if (shown || !creative.drawable) return
    val openLanding = LocalAdClick.current
    AdSplashOverlay(
        creative = creative,
        onClose = onShown,
        onClick = { if (creative.clickUrl.isNotBlank()) openLanding(creative.clickUrl) },
    )
}

@Composable
private fun AdSplashOverlay(
    creative: AdPlacement,
    onClose: () -> Unit,
    onClick: () -> Unit,
) {
    // 时长由后台「开屏时长」下发（1-10 秒）；为 0 或缺失时回落到默认 5 秒
    val total = creative.seconds.takeIf { it > 0 } ?: SPLASH_SECONDS
    var remain by remember(creative.imageUrl, total) { mutableStateOf(total) }
    // 倒计时到 0 关闭；不足 1 秒也让它自然结束，不额外调度
    LaunchedEffect(remain) {
        if (remain <= 0) onClose() else {
            delay(1_000)
            remain -= 1
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(starveilExtraColors.scrimStrong)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = creative.imageUrl,
            contentDescription = creative.title.ifBlank { "开屏广告" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        AdBadge(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Spacing.lg))
        Text(
            text = if (remain > 0) "跳过 $remain" else "跳过",
            style = MaterialTheme.typography.labelLarge,
            // 压在广告图上：深色半透明底 + 固定白字，浅色主题下不能跟着变浅
            color = starveilExtraColors.onImage,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(Spacing.lg)
                .clip(CircleShape)
                .background(starveilExtraColors.scrimStrong.copy(alpha = SKIP_BG_ALPHA))
                .clickable(onClick = onClose)
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        )
    }
}

/**
 * 打开广告落地页。只接 http/https：后台填了 `javascript:` 之类的串不能变成可执行入口。
 * 没有浏览器可处理时静默失败——广告打不开不该把页面弄崩。
 */
private fun Context.openLanding(url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "http" && uri.scheme != "https") return
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // 设备没有浏览器：广告落地页打不开属于可忽略，不影响播放与浏览
    }
}
