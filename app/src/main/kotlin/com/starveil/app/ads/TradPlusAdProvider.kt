package com.starveil.app.ads

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import com.starveil.core.common.ad.AdGateway
import com.starveil.core.common.ad.AdOutcome
import com.starveil.core.common.ad.AdSlot
import com.starveil.core.common.ad.TradPlusConfig
import com.tradplus.ads.base.bean.TPAdError
import com.tradplus.ads.base.bean.TPAdInfo
import com.tradplus.ads.base.bean.TPBaseAd
import com.tradplus.ads.open.TradPlusSdk
import com.tradplus.ads.open.banner.TPBanner
import com.tradplus.ads.open.interstitial.InterstitialAdListener
import com.tradplus.ads.open.interstitial.TPInterstitial
import com.tradplus.ads.open.nativead.TPNative
import com.tradplus.ads.open.reward.RewardAdListener
import com.tradplus.ads.open.reward.TPReward
import com.tradplus.ads.open.splash.SplashAdListener
import com.tradplus.ads.open.splash.TPSplash
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * TradPlus 广告出口。业务层只认 [AdGateway]，SDK 细节全部收在这里。
 *
 * 三条不能破的前提：
 * - 广告位类型在 TradPlus 后台创建时就固定了，客户端只传 adUnitId；用错类型只会拿到
 *   `errorCode 12 / no config`，所以每个位都严格建对应类型的对象。
 * - SDK 的回调都在主线程进来，缓存与 [showing] 因而只在主线程读写，不再加锁。
 * - [AdOutcome.Completed] 只代表"平台侧看完"，不是解锁依据；真正的放行要等服务端
 *   SSV 回调换签。
 */
class TradPlusAdProvider(
    private val appContext: Context,
    private val config: TradPlusConfig,
    private val activity: () -> Activity?,
    /**
     * 这次激励请求的身份（见 [RewardRequestContext]）。返回 null 表示游客或还没点解锁，
     * 此时不注入 SSV 参数，行为与加这个参数之前完全一致。
     */
    contextProvider: () -> AdRequestContext? = { null },
) : AdGateway {

    /** SSV 参数注入 + "这份缓存属于谁"的记录，与 TopOn 共用一套规则。 */
    private val ssv = RewardSsvParams(contextProvider)

    private val rewards = HashMap<String, TPReward>()
    private val interstitials = HashMap<String, TPInterstitial>()
    private val natives = HashMap<String, TPNative>()
    private val banners = HashMap<String, TPBanner>()
    private val showing = HashSet<AdSlot>()

    /** 开屏对象绑定了创建时的 Activity，不进 Map；换广告位或换宿主时销毁旧的。 */
    private var splash: TPSplash? = null
    private var splashUnitId: String? = null

    // `TPNative.nativeAd` 被厂商标了 deprecated，但它是唯一公开的"原生是否已加载"入口；
    // 拿到替代 API 之前只压这一处，不扩散成整类级别的忽略。
    @Suppress("DEPRECATION")
    override fun isReady(slot: AdSlot): Boolean {
        val target = resolve(slot) ?: return false
        return when (slot) {
            AdSlot.REWARD -> rewards[target.unitId]?.isReady() == true
            AdSlot.PAUSE -> interstitials[target.unitId]?.isReady() == true
            AdSlot.BANNER -> banners[target.unitId]?.isReady() == true
            // TPSplash 的 isReady 与其它类型同名，未创建时一律按"没货"处理
            AdSlot.SPLASH -> splash?.isReady() == true
            // 原生没有 isReady 这类接口，缓存里能拿出 TPBaseAd 才算有货
            AdSlot.FEED -> natives[target.unitId]?.nativeAd != null
        }
    }

    override fun preload(slot: AdSlot) {
        val target = resolve(slot) ?: return
        when (slot) {
            AdSlot.REWARD -> preloadReward(target)
            AdSlot.PAUSE -> preloadInterstitial(target)
            AdSlot.FEED -> preloadNative(target)
            AdSlot.BANNER -> preloadBanner(target)
            AdSlot.SPLASH -> preloadSplash(target)
        }
    }

    override suspend fun show(slot: AdSlot): AdOutcome {
        val target = resolve(slot) ?: return AdOutcome.Failed(REASON_UNAVAILABLE)
        // add 返回 false 说明同一位上还有一次 show 没结束，直接拒绝，避免 SDK 收到并发 show
        return if (showing.add(slot)) {
            try {
                withContext(Dispatchers.Main.immediate) { awaitShow(slot, target) }
            } finally {
                // 正常返回、抛异常、协程被取消都会走到这里，位上不会残留"忙碌"标记
                showing -= slot
            }
        } else {
            AdOutcome.Failed(REASON_BUSY)
        }
    }

    private suspend fun awaitShow(slot: AdSlot, target: ResolvedTarget): AdOutcome = when (slot) {
        AdSlot.REWARD -> awaitReward(target)
        AdSlot.PAUSE -> awaitInterstitial(target)
        AdSlot.SPLASH -> awaitSplash(target)
        // 原生与横幅是"页面自己渲染"的类型，本网关只负责回答有没有货
        AdSlot.FEED, AdSlot.BANNER -> AdOutcome.NoFill
    }

    private suspend fun awaitReward(target: ResolvedTarget): AdOutcome {
        val ad = rewardOf(target)
        // 缓存必须是在"这一集、这个人"的上下法拉回来的：setCustomParams 只在 loadAd
        // 那一刻进请求，而广告对象按 unitId 复用，对不上就重拉（本次按无货返回），
        // 否则回调认不出这次是谁，服务端只能回 403，用户白看一条。
        if (!ssv.prepare(target.unitId)) {
            // 身份变了：先写参数再重拉，旧的即使 ready 也不能拿去展示
            ssv.applyTradPlus(ad)
            ad.loadAd()
            return AdOutcome.NoFill
        }
        return if (!ad.isReady()) {
            // 顺手补一次加载：调用方通常会在 NoFill 之后引导用户下次再来
            ad.loadAd()
            AdOutcome.NoFill
        } else {
            suspendCancellableCoroutine { cont ->
                val session = RewardSession { cont.resumeIfActive(it) }
                ad.setAdListener(session)
                cont.invokeOnCancellation { session.detach() }
                runCatching { ad.showAd(target.activity, SCENE_ID) }.onFailure(session::abort)
            }
        }
    }

    /**
     * PAUSE 位用插屏：TradPlus 没有"暂停贴片"这种广告类型，暂停位的 adUnitId 在后台
     * 建成插屏广告位，这里就用 [TPInterstitial] 去对应。
     */
    private suspend fun awaitInterstitial(target: ResolvedTarget): AdOutcome {
        val ad = interstitials.getOrPut(target.unitId) {
            TPInterstitial(target.activity, target.unitId)
        }
        if (!ad.isReady()) {
            ad.loadAd()
            return AdOutcome.NoFill
        }
        return suspendCancellableCoroutine { cont ->
            val session = InterstitialSession { cont.resumeIfActive(it) }
            ad.setAdListener(session)
            cont.invokeOnCancellation { session.detach() }
            runCatching { ad.showAd(target.activity, SCENE_ID) }.onFailure(session::abort)
        }
    }

    /**
     * 开屏必须挂在一个 ViewGroup 上，且容器要接近全屏。provider 不持有页面布局，
     * 于是取 Activity 的 `android.R.id.content` 作为根容器。
     *
     * 代价是关闭后要 `removeAllViews()` 清干净，所以 SPLASH 只能用在"这个 Activity
     * 只承载开屏"的场景（冷启动页），否则会把页面内容一起清掉。
     */
    private suspend fun awaitSplash(target: ResolvedTarget): AdOutcome {
        val container = target.activity.findViewById<ViewGroup>(android.R.id.content)
        return if (container == null) AdOutcome.NoFill else awaitSplashIn(target, container)
    }

    private suspend fun awaitSplashIn(target: ResolvedTarget, container: ViewGroup): AdOutcome {
        val ad = splashOf(target)
        if (!ad.isReady()) {
            ad.loadAd(container)
            return AdOutcome.NoFill
        }
        return suspendCancellableCoroutine { cont ->
            val session = SplashSession(container) { cont.resumeIfActive(it) }
            ad.setAdListener(session)
            cont.invokeOnCancellation { session.detach() }
            runCatching { ad.showAd(container) }.onFailure(session::abort)
        }
    }

    private fun rewardOf(target: ResolvedTarget): TPReward =
        rewards.getOrPut(target.unitId) { TPReward(target.activity, target.unitId) }

    private fun splashOf(target: ResolvedTarget): TPSplash {
        val current = splash
        if (current != null && splashUnitId == target.unitId) return current
        current?.onDestroy()
        return TPSplash(target.activity, target.unitId).also {
            splash = it
            splashUnitId = target.unitId
        }
    }

    private fun preloadReward(target: ResolvedTarget) {
        if (rewards.containsKey(target.unitId)) return
        rewards[target.unitId] = TPReward(target.activity, target.unitId).also { ad ->
            // 预加载常常发生在播放页之前（AdHost 启动时），那时还没有身份，
            // 参数为空就不注入；等用户点解锁时 awaitReward 会发现缓存对不上并重拉。
            ssv.prepare(target.unitId)
            ssv.applyTradPlus(ad)
            ad.loadAd()
        }
    }

    private fun preloadInterstitial(target: ResolvedTarget) {
        if (interstitials.containsKey(target.unitId)) return
        interstitials[target.unitId] =
            TPInterstitial(target.activity, target.unitId).also { it.loadAd() }
    }

    private fun preloadNative(target: ResolvedTarget) {
        if (natives.containsKey(target.unitId)) return
        natives[target.unitId] = TPNative(target.activity, target.unitId).also { it.loadAd() }
    }

    private fun preloadBanner(target: ResolvedTarget) {
        if (banners.containsKey(target.unitId)) return
        banners[target.unitId] = TPBanner(target.activity).also { it.loadAd(target.unitId) }
    }

    private fun preloadSplash(target: ResolvedTarget) {
        val ad = splashOf(target)
        if (!ad.isReady()) ad.loadAd(null)
    }

    /**
     * 把位解析成"用哪个 Activity、哪个 adUnitId"。任何一个前提不成立都返回 null，
     * 上层据此给出 false / Failed，而不是让 SDK 在坏参数上抛异常。
     */
    private fun resolve(slot: AdSlot): ResolvedTarget? {
        val act = activity() ?: return null
        val unitId = config.slotOf(slot)
        return if (ensureSdk() && unitId.isNotBlank()) ResolvedTarget(act, unitId) else null
    }

    /** 宿主忘了 init 时补一次；[init] 用 getIsInit 判重，重复调用没有副作用。 */
    private fun ensureSdk(): Boolean = isInitialized() || init(appContext, config)

    companion object {
        /** 场景 ID 只用于后台统计"进入场景 / 展示次数"，客户端固定一个即可。 */
        private const val SCENE_ID = "starveil"

        private const val REASON_BUSY = "busy"
        private const val REASON_UNAVAILABLE = "unavailable"

        private val INIT_LOCK = Any()

        @Volatile
        private var initialized = false

        /**
         * 只初始化一次。TradPlus 是进程级单例，重复 init 会把已下发的配置与缓存冲掉，
         * 所以先用 getIsInit 判重。
         *
         * 返回 true 只表示"已把初始化交给 SDK"：initSdk 是异步的，真正能不能出广告
         * 由各广告位的 isReady 与加载回调决定。
         */
        fun init(context: Context, config: TradPlusConfig): Boolean {
            if (!config.initable) return false
            synchronized(INIT_LOCK) {
                initialized = startIfNeeded(context, config)
            }
            return initialized
        }

        fun isInitialized(): Boolean = initialized

        private fun startIfNeeded(context: Context, config: TradPlusConfig): Boolean {
            if (initialized || TradPlusSdk.getIsInit()) return true
            // appId 非法等情况下 SDK 会直接抛，这里收敛成 false，不把异常带给调用方
            return runCatching { TradPlusSdk.initSdk(context.applicationContext, config.appId) }
                .isSuccess
        }
    }
}

/** 一次 show 的目标：用哪个 Activity 展示、用哪个 adUnitId 请求。 */
private data class ResolvedTarget(val activity: Activity, val unitId: String)

/**
 * 一次 show 的回调收口。
 *
 * 平台回调会重复（失败后再来一次关闭、取消后迟到的回调），[settle] 只放行第一次，
 * 保证协程只 resume 一次；[detach] 让取消之后到达的回调变成空操作。
 */
private open class ShowSession(private val done: (AdOutcome) -> Unit) {

    private var settled = false

    fun settle(outcome: AdOutcome) {
        if (settled) return
        settled = true
        done(outcome)
    }

    fun fail(error: TPAdError?) = settle(AdOutcome.Failed(errorText(error)))

    /** SDK 在 Activity 已销毁时会直接抛，对外只报 [AdOutcome]，不让裸异常冒到 UI。 */
    fun abort(cause: Throwable) = settle(AdOutcome.Failed(cause.message ?: cause.javaClass.simpleName))

    fun detach() {
        settled = true
    }
}

private fun errorText(error: TPAdError?): String =
    if (error == null) "unknown" else "${error.errorCode}: ${error.errorMsg}"

/**
 * 激励视频回调。
 *
 * 只有 [onAdReward] 能算"看完"：[onAdVideoEnd] 只说明视频播完，用户仍可能在发奖前关掉；
 * [onAdClosed] 只说明关闭，可能是中途跳过。所以 Completed 只挂在 onAdReward 上，
 * 先关闭后发奖这种顺序在真机上不会出现，靠 [ShowSession.settle] 兜住也只认第一次。
 */
private class RewardSession(done: (AdOutcome) -> Unit) : ShowSession(done), RewardAdListener {

    override fun onAdReward(tpAdInfo: TPAdInfo?) = settle(AdOutcome.Completed)

    override fun onAdClosed(tpAdInfo: TPAdInfo?) = settle(AdOutcome.Skipped)

    override fun onAdFailed(tpAdError: TPAdError?) = fail(tpAdError)

    override fun onAdVideoError(tpAdInfo: TPAdInfo?, tpAdError: TPAdError?) = fail(tpAdError)

    override fun onAdLoaded(tpAdInfo: TPAdInfo?) = Unit

    override fun onAdClicked(tpAdInfo: TPAdInfo?) = Unit

    override fun onAdImpression(tpAdInfo: TPAdInfo?) = Unit

    override fun onAdVideoStart(tpAdInfo: TPAdInfo?) = Unit

    override fun onAdVideoEnd(tpAdInfo: TPAdInfo?) = Unit
}

/** 插屏回调。插屏没有"发奖"概念，关闭即 [AdOutcome.Completed]。 */
private class InterstitialSession(done: (AdOutcome) -> Unit) :
    ShowSession(done), InterstitialAdListener {

    override fun onAdClosed(tpAdInfo: TPAdInfo?) = settle(AdOutcome.Completed)

    override fun onAdFailed(tpAdError: TPAdError?) = fail(tpAdError)

    override fun onAdVideoError(tpAdInfo: TPAdInfo?, tpAdError: TPAdError?) = fail(tpAdError)

    override fun onAdLoaded(tpAdInfo: TPAdInfo?) = Unit

    override fun onAdClicked(tpAdInfo: TPAdInfo?) = Unit

    override fun onAdImpression(tpAdInfo: TPAdInfo?) = Unit

    override fun onAdVideoStart(tpAdInfo: TPAdInfo?) = Unit

    override fun onAdVideoEnd(tpAdInfo: TPAdInfo?) = Unit
}

/**
 * 开屏回调。关闭时把容器清空，否则残留的广告 View 会一直盖在启动页上。
 *
 * `SplashAdListener` 在 16.9.0.1 里是 **class** 不是 interface（已用 javap 核对），
 * 所以这里只能单继承，把结果委托给 [ShowSession]，不能像激励/插屏那样再实现一个接口。
 */
private class SplashSession(
    private val container: ViewGroup,
    done: (AdOutcome) -> Unit,
) : SplashAdListener() {

    private val session = ShowSession(done)

    override fun onAdClosed(tpAdInfo: TPAdInfo?) {
        container.removeAllViews()
        session.settle(AdOutcome.Completed)
    }

    override fun onAdLoadFailed(tpAdError: TPAdError?) = session.fail(tpAdError)

    override fun onAdClicked(tpAdInfo: TPAdInfo?) = Unit

    override fun onAdImpression(tpAdInfo: TPAdInfo?) = Unit

    override fun onAdLoaded(tpAdInfo: TPAdInfo?, tpBaseAd: TPBaseAd?) = Unit

    /** 协程取消/异常时由调用方收口：委托给 [ShowSession]，避免迟到回调再 resume 一次。 */
    fun detach() = session.detach()

    fun abort(cause: Throwable) = session.abort(cause)
}

/** 只把结果交给还活着的协程；已经取消的 show 不需要任何回应。 */
private fun CancellableContinuation<AdOutcome>.resumeIfActive(outcome: AdOutcome) {
    if (isActive) resume(outcome)
}
