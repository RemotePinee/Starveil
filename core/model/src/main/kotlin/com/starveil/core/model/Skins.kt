package com.starveil.core.model

/**
 * 多皮肤。
 *
 * 皮肤标识用 `app_theme_ui`（字符串 "1".."5"）。早期实现为每套皮肤复制一份
 * Fragment + 布局（4 套首页 / 3 套排行榜 / 6 套用户中心）。这里把"选哪套"收敛成数据，
 * 页面只保留一套实现 + 变体配置。
 *
 * 两条旧行为必须原样保留：
 * 1. 本地覆盖优先：本地存过皮肤时**完全忽略**服务端值
 * 2. 服务端四个字段任一为 null 时历史版本判盗版并直接结束进程
 * 新客户端不许杀进程，改用
 * [SkinResolution.contractBroken] 交给 UI 提示。
 */
object Skins {

    /** 服务端取值 → 皮肤。未知值/空串按的 default 分支处理。 */
    fun resolve(serverValue: String?, localOverride: String? = null): SkinResolution {
        val local = localOverride?.trim().orEmpty()
        val source = if (local.isNotEmpty()) local else serverValue?.trim().orEmpty()
        val fromLocal = local.isNotEmpty()
        return SkinResolution(
            skin = fromCode(source),
            fromLocalOverride = fromLocal,
            contractBroken = serverValue.isNullOrEmpty() && !fromLocal,
            unknownCode = source.isNotEmpty() && fromCode(source) == SkinId.DEFAULT && source !in KNOWN_CODES,
        )
    }

    private val KNOWN_CODES = setOf("1", "2", "3", "4", "5")

    private fun fromCode(code: String): SkinId = when (code) {
        "1" -> SkinId.YOUKU
        "2" -> SkinId.KATONG
        "3" -> SkinId.BILIBILI
        "4" -> SkinId.BILIBILI2
        "5" -> SkinId.AQC
        else -> SkinId.DEFAULT
    }

    /**
     * 首页变体。
     *
     * 旧映射：1/2/5 → HomeFragmentB，3/4 → HomeFragmentD，其它 → HomeFragmentC。
     * 也就是说 5 套皮肤在首页只落成 3 个实现——这正是"皮肤不等于页面"的证据。
     */
    fun home(variant: SkinId): HomeVariant = when (variant) {
        SkinId.YOUKU, SkinId.KATONG, SkinId.AQC -> HomeVariant.CARD_GRID_B
        SkinId.BILIBILI, SkinId.BILIBILI2 -> HomeVariant.CARD_GRID_D
        SkinId.DEFAULT -> HomeVariant.CARD_GRID_C
    }

    /** 排行榜变体：1/2/5 → B，3/4 → 默认，其它 → C。 */
    fun rank(skin: SkinId): RankVariant = when (skin) {
        SkinId.YOUKU, SkinId.KATONG, SkinId.AQC -> RankVariant.CARD_B
        SkinId.BILIBILI, SkinId.BILIBILI2 -> RankVariant.DEFAULT
        SkinId.DEFAULT -> RankVariant.CARD_C
    }

    /** 用户中心变体：只有这一段是一套皮肤一个实现。 */
    fun userCenter(skin: SkinId): UserCenterVariant = when (skin) {
        SkinId.YOUKU -> UserCenterVariant.B
        SkinId.KATONG -> UserCenterVariant.C
        SkinId.BILIBILI -> UserCenterVariant.D
        SkinId.BILIBILI2 -> UserCenterVariant.E
        SkinId.AQC -> UserCenterVariant.F
        SkinId.DEFAULT -> UserCenterVariant.A
    }
}

/** 皮肤标识。命名按旧皮肤的调试页顺序。 */
enum class SkinId {
    /** 默认（爱奇艺风）。旧 default 分支。 */
    DEFAULT,

    /** "1" 优酷风。 */
    YOUKU,

    /** "2" 卡通风。 */
    KATONG,

    /** "3" B 站风。 */
    BILIBILI,

    /** "4" B 站风二套。 */
    BILIBILI2,

    /** "5" AQC 风。 */
    AQC,
}

enum class HomeVariant { CARD_GRID_C, CARD_GRID_B, CARD_GRID_D }

enum class RankVariant { DEFAULT, CARD_B, CARD_C }

enum class UserCenterVariant { A, B, C, D, E, F }

/**
 * 解析结果。`contractBroken` 对应历史版本判盗版的条件，
 * `unknownCode` 表示服务端下发了没见过的皮肤号——都应回退默认皮肤而不是崩。
 */
data class SkinResolution(
    val skin: SkinId,
    val fromLocalOverride: Boolean,
    val contractBroken: Boolean,
    val unknownCode: Boolean,
)
