package com.starveil.core.model

/**
 * 付费墙闸门。
 *
 * 提取自旧播放页与竖屏页：判定完全由服务端下发，
 * 客户端只负责"什么时候拦"和"拦下来显示哪一套文案"。
 *
 * 服务端已把「试看状态 0/1/2」改成**单一付费墙开关**：`status` 只会是 0（免费）
 * 或 1（需解锁），`trysee` 秒数废弃，888 哨兵随之下线。
 */
object TrySeeRules {

    /** 免费观看，不弹闸门。 */
    const val STATUS_FREE = 0

    /** 需要 VIP。 */
    const val STATUS_NEED_VIP = 1

    /** 需要单集付费。 */
    const val STATUS_NEED_PAY = 2

    /**
     * 服务端 `status` + `user_video`（剩余免费次数）→ 观看权限。
     *
     * 单一开关后 `status` 只会是 0（免费）或 1（需解锁）；
     * 剩余免费次数 > 0 也免费；未知 status 按免费处理。
     * `STATUS_NEED_PAY`（2）只是历史取值，服务端不会再下发。
     */
    fun access(status: Int, freeCount: Int): TrySeeAccess = when {
        status == STATUS_FREE -> TrySeeAccess.FREE
        freeCount > 0 -> TrySeeAccess.FREE
        status == STATUS_NEED_VIP -> TrySeeAccess.NEED_VIP
        status == STATUS_NEED_PAY -> TrySeeAccess.NEED_PAY
        else -> TrySeeAccess.FREE
    }

    /**
     * 播放进度是否已越过允许的可看时长。
     *
     * 服务端不再下发有效秒数（`trysee` 恒 0 / 缺省），所以 [trySeeMinutes] <= 0
     * 视为"没有可看窗口"：需解锁的集一开始就拦。历史哨兵 888 已随单开关下线，
     * 现在只是普通的 888 分钟，没有特殊含义。
     */
    fun isExceeded(positionMs: Long, trySeeMinutes: Int): Boolean =
        trySeeMinutes <= 0 || positionMs > trySeeMinutes.toLong() * 60_000
}

/**
 * 拦截后的三种取值。
 *
 * 服务端已改成单开关，`NEED_VIP` / `NEED_PAY` 只是历史取值（status 1 / 2），
 * **两档文案必须一致**：不要在展示层再按枚举分叉出两套说辞。解锁方式可能是会员、
 * 积分或广告，客户端不从 status 反推。文案见 [TrySeeCopy]。
 */
enum class TrySeeAccess {
    FREE,
    NEED_VIP,
    NEED_PAY,
}

/**
 * 付费墙接口的一次判定结果。
 *
 * [access] 必须由 [TrySeeRules.access] 算出，与播放器 tick 的判定共用同一口径。
 * [trySeeMinutes] **已废弃**：服务端不再下发有效秒数，客户端也不再拿它拼文案；
 * 字段与解析保留只为兼容旧响应，新代码不要读它做展示。
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

/**
 * 付费墙文案：**一套覆盖三种解锁方式**。
 *
 * 服务端已改成单一开关，`status` 只回答"要不要解锁"，不回答"怎么解锁"——
 * 会员、积分兑换、看广告都可能放行，所以文案必须并列三种出口，
 * 不能只写"开通会员"。详情页提示条、播放器浮层、竖屏剧集页共用这一份，别再各写一句。
 */
object TrySeeCopy {
    const val TITLE = "需要解锁"
    const val HINT = "开通会员、积分兑换或看广告解锁后可看全集"
}
