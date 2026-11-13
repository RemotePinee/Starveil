package com.starveil.feature.player

import com.starveil.core.model.CastDecision
import com.starveil.core.model.CastRules
import com.starveil.core.model.CastSource

/**
 * 当前这一集"能不能投、投什么"。
 *
 * 页面只读它，不去判断格式、也不去读红果开关：那些都在 [CastRules] 与 ViewModel 里。
 * [decision] 是 `Rejected` 时不显示入口——不是点了再报错。
 */
data class CastTarget(
    val url: String,
    val title: String,
    val decision: CastDecision,
) {
    val canCast: Boolean get() = decision is CastDecision.Allowed

    /** 不可投时给用户的那句话；可投时为空。 */
    val rejectReason: String?
        get() = (decision as? CastDecision.Rejected)?.reason
}

/**
 * 算出一条播放地址的投屏目标；不可投也返回对象（带原因），由 UI 决定是否显示。
 *
 * `needsHeaders` 一律按 false：我们的源由服务端按域签名，地址本身可直接拉；
 * 真要接第三方官解链接（需 Referer），在调用处传 true 即可，规则会自动拒绝。
 * `remainingSeconds` 传最大值：CMS 播放地址在起播时生成，电视拿到就去拉，
 * 没有需要我们提前判断的固定时效。
 */
internal fun castTargetOf(url: String?, title: String, needsClientDecryption: Boolean): CastTarget? {
    if (url.isNullOrBlank()) return null
    val source = CastSource(
        url = url,
        mime = castMimeOf(url),
        needsHeaders = false,
        remainingSeconds = Long.MAX_VALUE,
    )
    return CastTarget(
        url = url,
        title = title.ifBlank { "正在播放" },
        decision = CastRules.decide(source, needsClientDecryption),
    )
}

/**
 * 按扩展名推 MIME，供 DIDL 的 `protocolInfo` 与投屏规则共用。
 *
 * 只认 m3u8 / mp4 两种可投格式；其它一律给 mp4，让 [CastRules] 按格式拒绝，
 * 而不是让电视拿到一个它解不了的 `protocolInfo`。
 */
internal fun castMimeOf(url: String): String {
    val path = url.substringBefore('?').lowercase()
    return when {
        path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
        path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
        else -> "video/mp4"
    }
}

/** 投屏面板对页面的三个动作：收起、投上后暂停本地、退出时回到电视端进度。 */
internal class CastOverlayCallbacks(
    val onDismiss: () -> Unit,
    val onConnected: () -> Unit,
    val onRestorePosition: (Long) -> Unit,
)
