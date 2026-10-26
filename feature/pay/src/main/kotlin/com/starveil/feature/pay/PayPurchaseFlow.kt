package com.starveil.feature.pay

import com.starveil.core.common.AppResult
import com.starveil.core.data.PayRepository
import com.starveil.core.model.CardRedeem
import com.starveil.core.model.CardType

/**
 * 单集购买与卡密兑换。
 *
 * 两条都是"提交一次、返回成功/失败"的短流程，和会员下单的查单逻辑无关，
 * 单独成类是为了让 `PayViewModel` 保持在行数上限内，也便于分别测试。
 *
 * ：单集 ，卡密 。
 */
internal class PayPurchaseFlow(private val payRepository: PayRepository) {

    suspend fun buyEpisode(args: PayArgs): AppResult<Unit> =
        payRepository.buyEpisode(
            vodId = args.vodId,
            episodeNumber = args.episodeNumber,
            sourceIndex = args.sourceIndex,
        )

    suspend fun redeemCard(code: String): AppResult<CardRedeem> = payRepository.redeemCard(code)
}

/**
 * 卡密兑换的成功提示。
 *
 * 不分卡类型，金币卡也提示"会员已到账"；
 * 后端从 `v1.user/buy` 的 `data` 里回传类型与面值后，这里按类型给准确文案。
 * 类型缺失（老后端）时不编造天数/金币数。
 */
internal fun CardRedeem.redeemMessage(): String = when (cardType) {
    CardType.VIP -> "兑换成功，会员已延长 $cardValue 天"
    CardType.GOLD -> "兑换成功，获得 $cardValue 金币"
    null -> "兑换成功"
}

/** 卡密输入长度上限：后台生成的是 16 位十六进制串，32 只作为手输容错上限。 */
internal const val MAX_CARD_LENGTH = 32
