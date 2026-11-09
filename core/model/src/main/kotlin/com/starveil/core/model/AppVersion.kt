package com.starveil.core.model

/**
 * 在线更新：后端下发的最新版本。
 *
 * [code] 为 0 表示后台没配版本信息，客户端直接跳过检查——
 * 老后端没有这个配置，不能因为它缺失就弹"有新版本"。
 */
data class AppVersion(
    val code: Int = 0,
    val name: String = "",
    val url: String = "",
    /** 安装前的校验值；空串表示后端没提供，跳过校验但会记日志。 */
    val sha256: String = "",
    val force: Boolean = false,
    val changelog: String = "",
) {
    /** 是否比当前安装的版本新。 */
    fun isNewerThan(currentCode: Int): Boolean = code > currentCode && code > 0
}
