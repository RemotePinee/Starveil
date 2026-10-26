package com.starveil.core.common

/**
 * Media3 错误码 → 用户能看懂的一句话。
 *
 * 以前直接把 `errorCodeName`（例如 `ERROR_CODE_IO_BAD_HTTP_STATUS`）塞进 UI，
 * 用户看到的是源码常量，等于没提示。这里只做文案映射，不改错误处理逻辑。
 *
 * 放在 `:core:common` 是因为点播（`:feature:player`）与直播（`:feature:live`）
 * 都要用，而 feature 之间不能互相依赖（见 AGENTS.md 第 1 节）。
 */

/** 点播：`triedOtherSources` 为 true 时，文案里会带上"已自动换线路"的事实。 */
fun playerErrorMessage(code: String, triedOtherSources: Boolean = true): String = when {
    code.contains("BAD_HTTP_STATUS") -> if (triedOtherSources) {
        "这条线路被源站拒绝了（HTTP 错误），已自动尝试其它线路"
    } else {
        "视频暂时加载失败，请重试或切换短剧"
    }
    code.contains("IO_UNSPECIFIED") || code.contains("IO_NETWORK") -> "线路连不上，检查网络后重试"
    code.contains("TIMEOUT") -> "线路响应超时"
    code.contains("PARSING_CONTAINER") || code.contains("PARSING_MANIFEST") -> "播放地址无效"
    code.contains("DECODING") -> "当前设备解不了这个视频格式"
    code.contains("AUDIO") -> "音频轨道加载失败"
    code.contains("DRM") -> "版权保护内容无法播放"
    else -> "播放失败，请重试"
}

/**
 * 直播：比点播更需要说清"是源的问题还是网络的问题"，因为直播没有备用线路可换。
 *
 * [httpStatus] 由调用方从 Media3 的异常链里取（`InvalidResponseCodeException.responseCode`），
 * 有它就能把 403/404/5xx 分开讲，比笼统一句"加载失败"有用得多。
 */
fun liveErrorMessage(code: String, httpStatus: Int? = null): String =
    httpStatusMessage(httpStatus) ?: mediaCodeMessage(code)

private fun httpStatusMessage(status: Int?): String? = when {
    status == null -> null
    status == 401 || status == 403 -> "源站拒绝访问（HTTP $status），这条线路可能被限制或已过期"
    status == 404 || status == 410 -> "频道地址已失效（HTTP $status），源站不再提供"
    status >= 500 -> "源站出错（HTTP $status），过一会儿再试"
    status >= 400 -> "源站返回 HTTP $status，这个台暂时看不了"
    else -> null
}

private fun mediaCodeMessage(code: String): String = when {
    code.contains("CLEARTEXT") -> "这个频道是 http 明文源，App 只允许 https"
    code.contains("BAD_HTTP_STATUS") -> "源站返回错误状态码，这个台暂时看不了"
    code.contains("FILE_NOT_FOUND") -> "找不到这个频道的地址（域名解析失败或域名失效）"
    code.contains("TIMEOUT") -> "连源站超时，网络或线路不通"
    code.contains("NETWORK") -> "连不上源站：网络或线路不通，换个台试试"
    code.contains("PARSING") -> "这个地址不是有效的直播播放列表"
    code.contains("DECODING") -> "当前设备解不了这个视频格式"
    else -> "播放失败，换个台试试"
}
