package com.starveil.core.hongguo

/**
 * 中转地址解析。
 *
 * 采集端落库的地址形如 `/<前缀>/<series_id>/<episode>.mp4`，播放时在同一个地址上加
 * `?d=1` 就能拿到材料（地址 + spade_a + kid），不用再解析一次 series_id 去调别的接口。
 *
 * **前缀不能写死**：它由采集端的部署决定（`hongguo`、`hema` 都可能），这里只要求
 * "一段字母开头的路径 + 剧 id + 集号"，叫什么交给部署方。
 */
object HongguoRelay {

    private val RELAY = Regex("""/[A-Za-z][A-Za-z0-9_-]*/(\d+)/(\d+)(?:\.\w+)?$""")

    /** 正则里第几个捕获组是剧 id / 集号。 */
    private const val SERIES_GROUP = 1
    private const val EPISODE_GROUP = 2

    /** 从中转地址取 (series_id, episode)；不是中转地址返回 null。 */
    fun parse(url: String?): Pair<String, String>? =
        url?.takeIf { it.isNotBlank() }
            ?.substringBefore('?')
            ?.let { RELAY.find(it) }
            ?.let { it.groupValues[SERIES_GROUP] to it.groupValues[EPISODE_GROUP] }

    /** 同一个地址加 ?d=1，变成材料接口。 */
    fun materialUrl(url: String): String? {
        if (parse(url) == null) return null
        return if (url.contains('?')) "$url&d=1" else "$url?d=1"
    }
}
