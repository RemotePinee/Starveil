package com.starveil.core.network.mapper

import com.starveil.core.model.TrySeeAccess
import com.starveil.core.model.TrySeeGate
import com.starveil.core.model.TrySeeRules
import com.starveil.core.network.dto.TrySeeDto

/**
 * 试看 DTO -> 领域模型，判定口径与旧播放页一致：
 * 直接调 [TrySeeRules.access]，不在映射层另写一套 if-else。
 *
 * 旧播放页还叠加了"用户是不是 VIP""本地还有没有免看次数"两条本地判断
 * 仓库层拿不到这两项，不在这一层猜。
 */
fun TrySeeDto.toDomain(): TrySeeGate {
    val access = TrySeeRules.access(status = status, freeCount = freeCount)
    return TrySeeGate(
        access = access,
        trySeeMinutes = trySeeMinutes,
        // 老后端没有能力位：退回"需会员只给会员按钮、需积分只给积分按钮"的旧行为
        canUpgrade = canVip?.let { it == 1 } ?: (access == TrySeeAccess.NEED_VIP),
        canRedeemPoints = canPoints?.let { it == 1 } ?: (access == TrySeeAccess.NEED_PAY),
        canWatchAd = canAd == 1,
        pointsPrice = pointsPrice,
    )
}
