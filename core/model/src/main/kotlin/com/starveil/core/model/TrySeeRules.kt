package com.starveil.core.model

/**
 * 试看/付费闸门。
 *
 * 提取自 与
 * 判定完全由服务端下发，
 * 客户端只负责"何时算超时"和"超时后显示哪一种浮层"。
 *
 * 与的差别：
 * - 历史版本 `setProgress` 每个 tick 都 `pause()` 一次，
 * 只返回判定结果，节流由调用方负责，避免重复暂停。
 * - 魔法值 888 保留原语义（服务端用它表示"直接拦截"），但改名成常量。
 */
object TrySeeRules {

    /** 服务端用 888 表示"不看时长，直接拦截"。 */
    const val BLOCK_IMMEDIATELY_MINUTES = 888

    /** 免费观看，不弹闸门。 */
    const val STATUS_FREE = 0

    /** 需要 VIP。 */
    const val STATUS_NEED_VIP = 1

    /** 需要单集付费。 */
    const val STATUS_NEED_PAY = 2

    /**
     * 服务端 `status` + `user_video`（剩余免费次数）→ 观看权限。
     *
     * `status == 0` 免费；剩余免费次数 > 0 也免费；
     * 未知 status 按免费处理（只写了 1 和 2 两个分支）。
     */
    fun access(status: Int, freeCount: Int): TrySeeAccess = when {
        status == STATUS_FREE -> TrySeeAccess.FREE
        freeCount > 0 -> TrySeeAccess.FREE
        status == STATUS_NEED_VIP -> TrySeeAccess.NEED_VIP
        status == STATUS_NEED_PAY -> TrySeeAccess.NEED_PAY
        else -> TrySeeAccess.FREE
    }

    /**
     * 播放进度是否已越过试看时长。
     *
     * 用 `position / 1000f > trySeeTime * 60`（float 比较，有精度误差），
     * 换成整数毫秒比较，边界更明确。
     */
    fun isExceeded(positionMs: Long, trySeeMinutes: Int): Boolean {
        if (trySeeMinutes == BLOCK_IMMEDIATELY_MINUTES) return true
        return trySeeMinutes > 0 && positionMs > trySeeMinutes.toLong() * 60_000
    }
}

/** 试看结束后的三种出路。 */
enum class TrySeeAccess {
    FREE,
    NEED_VIP,
    NEED_PAY,
}

/**
 * 试看接口的一次判定结果。
 *
 * [access] 必须由 [TrySeeRules.access] 算出，与播放器 tick 的判定共用同一口径；
 * [trySeeMinutes] 是服务端下发的分钟数，值为 888 表示不看时长直接拦截
 * （见 [TrySeeRules.BLOCK_IMMEDIATELY_MINUTES]）。
 *
 * 后四个字段是浮层按钮能力位，由服务端下发：
 * - [canUpgrade]：会员体系开启且当前被拦时才有"开通会员"；
 * - [canRedeemPoints]：配了单集积分价才有"积分兑换本集"；
 * - [canWatchAd]：广告解锁预留位，接入 SDK 与 SSV 前服务端恒 0；
 * - [pointsPrice]：单集所需积分，0 表示没配价。
 *
 * 默认值按 [access] 回退，保证老后端（无这些字段）行为不变。
 */
data class TrySeeGate(
    val access: TrySeeAccess,
    val trySeeMinutes: Int,
    val canUpgrade: Boolean = access == TrySeeAccess.NEED_VIP,
    val canRedeemPoints: Boolean = access == TrySeeAccess.NEED_PAY,
    val canWatchAd: Boolean = false,
    val pointsPrice: Int = 0,
)
