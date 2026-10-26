package com.starveil.core.common.ad

/**
 * 单个广告位：位 ID + 直投资材。
 *
 * 后端「运营设置 → 广告」按位下发，`status == 1` 表示位开启（总开关开且位 ID 或图片非空，
 * 由服务端 `Startup::adSlot()` 算好）。客户端不再推算开关，只决定"能不能画"：
 * - [slotId] 非空但 [imageUrl] 为空：留给接入 SDK 之后用，现在画不出来 → 隐藏；
 * - [imageUrl] 非空：直投素材，直接用图片渲染。
 */
data class AdPlacement(
    val slotId: String = "",
    val enabled: Boolean = false,
    val imageUrl: String = "",
    val clickUrl: String = "",
    val title: String = "",
    /** 信息流广告第二行文案；其它位为空。 */
    val subtitle: String = "",
    /** 信息流插播间隔：每 N 部剧插 1 屏；0 表示不插。其它位恒 0。 */
    val interval: Int = 0,
    /** 激励视频看满多少秒算完成；其它位恒 0。 */
    val seconds: Int = 0,
) {
    /** 有图才画；位 ID 单独存在时留待 SDK 接入。 */
    val drawable: Boolean get() = AdPolicy.drawable(enabled, imageUrl)

    /** 信息流间隔收敛到 0..[MAX_INTERVAL]，后端填了离谱数字也不会把网格插爆。 */
    val feedInterval: Int get() = interval.coerceIn(0, MAX_INTERVAL)

    /** 激励倒计时收敛到 1..[MAX_SECONDS]；后端填 0 时退回默认，不会出现"0 秒自动完成"。 */
    val rewardSeconds: Int get() = if (seconds <= 0) DEFAULT_SECONDS else seconds.coerceAtMost(MAX_SECONDS)

    /**
     * 暂停贴片的强制观看秒数：**0 表示不限制**（贴片一出现就能关）。
     *
     * 与 [rewardSeconds] 的差别是有意为之：激励是"看满才算完成"，0 必须兜底成默认值；
     * 暂停贴片只是"别一暂停就关掉"，运营可以关掉这项限制。
     */
    val pauseSeconds: Int get() = seconds.coerceIn(0, MAX_SECONDS)

    /** 信息流强制停留秒数：0 表示不限制（这一屏可以立刻滑走）。 */
    val feedSeconds: Int get() = seconds.coerceIn(0, MAX_SECONDS)

    companion object {
        const val DEFAULT_SECONDS = 5
        const val MAX_SECONDS = 30
        const val MAX_INTERVAL = 20
        const val DEFAULT_INTERVAL = 8
    }
}

/**
 * 五个广告位的集合。启动配置里的 `ads` 整体映射成它，页面按位取，不再逐字段拆布尔。
 *
 * [rewardVideoUrl] 与 [consentText] 一样是启动配置里的**全局**字段（不属于单个广告位）：
 * 它表示"激励位配了直投视频"，有值时激励位走服务端签发会话的视频链路，
 * 没有时退回直投图片 + 倒计时（见 `:app` 的 `RewardImageOverlay` / `RewardVideoOverlay`）。
 *
 * [NONE] 是"全关"：缺字段、老后端、拉取失败都用它，页面因此自然不渲染任何广告。
 */
data class AdPlacements(
    val splash: AdPlacement = AdPlacement(),
    val banner: AdPlacement = AdPlacement(),
    val feed: AdPlacement = AdPlacement(),
    val reward: AdPlacement = AdPlacement(),
    val pause: AdPlacement = AdPlacement(),
    /** 聚合平台选择与两套 SDK 的凭证/广告位。没接 SDK 时是 [AdPlatformConfig.NONE]。 */
    val platform: AdPlatformConfig = AdPlatformConfig.NONE,
    /** 聚合平台 AppID 的展示值（老字段 `application_id`）；渠道排查用，不参与渲染。 */
    val sdkApplicationId: String = "",
    /** 老契约的启动图 HTML；当前客户端不执行 HTML，保留字段供后端兼容。 */
    val startupHtml: String = "",
    /** 广告隐私弹窗正文（后台「运营设置 → 广告」可改）；空串表示用客户端默认文案。 */
    val consentText: String = "",
    /**
     * 直投激励视频地址。空串 = 没配视频，激励位只看 [AdPlacement.imageUrl]。
     *
     * 实际播放地址以 `v1.ad/session` 返回的 `video_url` 为准，这个字段只用来判断
     * "这个站有没有视频链路"（也决定激励位按钮是否出现，见 `LocalAdGateway.isReady`）。
     */
    val rewardVideoUrl: String = "",
) {
    fun of(slot: AdSlot): AdPlacement = when (slot) {
        AdSlot.SPLASH -> splash
        AdSlot.BANNER -> banner
        AdSlot.FEED -> feed
        AdSlot.REWARD -> reward
        AdSlot.PAUSE -> pause
    }

    /**
     * 激励位有没有可播的直投视频。
     *
     * 只认"配了地址"这一件事：视频链路由服务端签发会话、校验进度并写解锁记录，
     * 客户端不在这里判断用户够不够资格。
     */
    val hasRewardVideo: Boolean get() = rewardVideoUrl.isNotBlank()

    /** 任一广告位可画时为真，供"要不要为广告留内存/请求埋点"这类整体判断。 */
    val anyDrawable: Boolean
        get() = listOf(splash, banner, feed, reward, pause).any { it.drawable }

    companion object {
        val NONE = AdPlacements()
    }
}
