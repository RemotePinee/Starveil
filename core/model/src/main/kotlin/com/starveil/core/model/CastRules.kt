package com.starveil.core.model

/**
 * 一次投屏要交给电视的源。
 *
 * 投屏是**电视自己去拉流**，所以除了地址本身，还要带上电视判不了、只有我们知道的几件事：
 * - [mime]：电视按它选解码器，猜错就是黑屏；
 * - [needsHeaders]：源要不要 Referer / UA / Cookie。DLNA 没有带自定义头的标准方式，
 *   有依赖就投不了，必须在按钮上就说清楚；
 * - [remainingSeconds]：签名地址的有效期。手机拿到链接立刻能播，不代表电视 20 秒后
 *   去拉还活着，剩余太短就别投了。
 */
data class CastSource(
    val url: String,
    val mime: String,
    val needsHeaders: Boolean = false,
    val remainingSeconds: Long,
)

/** 判定结果：要么能投，要么给一句用户能看懂的原因（按钮置灰时显示）。 */
sealed interface CastDecision {
    data object Allowed : CastDecision
    data class Rejected(val reason: String) : CastDecision
}

/**
 * 一条源能不能投屏。
 *
 * 白名单而不是黑名单：目前只认 HLS（m3u8）与 MP4。电视端对 DASH、FLV、MKV 的支持率
 * 断崖式下降，放进来只会多出"投过去黑屏"的客诉——以后真要支持，在这里加一种格式，
 * 而不是让各页面自己判断。
 *
 * 竖屏短剧不在这条规则里：那是产品层面的取舍（投到电视是左右黑边），
 * 由播放页决定是否显示入口。
 */
object CastRules {

    /** 签名剩余少于此值就不投：电视还没拉到流，链接就过期了。 */
    const val MIN_REMAINING_SECONDS = 300L

    private val ALLOWED_MIMES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "video/mp4",
    )

    fun decide(source: CastSource, needsClientDecryption: Boolean): CastDecision = when {
        needsClientDecryption -> CastDecision.Rejected(REASON_ENCRYPTED)
        !isHttp(source.url) -> CastDecision.Rejected(REASON_NOT_DIRECT)
        source.mime.lowercase() !in ALLOWED_MIMES -> CastDecision.Rejected(REASON_FORMAT)
        source.needsHeaders -> CastDecision.Rejected(REASON_HEADERS)
        source.remainingSeconds < MIN_REMAINING_SECONDS -> CastDecision.Rejected(REASON_EXPIRED)
        else -> CastDecision.Allowed
    }

    private fun isHttp(url: String): Boolean {
        val link = url.trim()
        return link.startsWith("http://", ignoreCase = true) ||
            link.startsWith("https://", ignoreCase = true)
    }

    const val REASON_ENCRYPTED = "该片源需要应用内解密，电视无法直接播放"
    const val REASON_NOT_DIRECT = "该片源不是可直链播放的地址"
    const val REASON_FORMAT = "该片源格式不支持投屏（仅支持 m3u8 / mp4）"
    const val REASON_HEADERS = "该片源需要额外鉴权，电视无法直接拉取"
    const val REASON_EXPIRED = "片源链接即将过期，请重新进入播放后再投屏"
}
