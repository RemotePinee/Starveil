package com.starveil.core.network.mapper

import com.starveil.core.model.WithdrawTips
import com.starveil.core.network.dto.GoldTipDto

/**
 * 金币提现 DTO -> 领域模型。
 *
 * 后端的提现说明是**中文散文**，不是字段：`msg` 是"最低提现20元"，
 * `info` 是"当前可提现金额5000元，提现比例10金币=1元"（后端注释）。
 * 页面要的是可判断的数值（金额下限、余额），所以在这一层做解析，
 * 解析不到就降级成 0——从不解析，只是把 `info` 原样显示
 * 所以解析失败不能算错误。
 *
 * 正则刻意宽容：后端可能写成"最低提现金额20元"，只要求"最低提现"后面
 * 到第一个数字之间是非数字字符。
 */
fun GoldTipDto.toWithdrawTips(): WithdrawTips {
    val hint = msg.orEmpty().trim()
    val info = info.orEmpty().trim()
    return WithdrawTips(
        minAmountYuan = firstNumber(MIN_AMOUNT_PATTERN, hint) ?: firstNumber(MIN_AMOUNT_PATTERN, info) ?: 0,
        availableYuan = firstNumber(AVAILABLE_PATTERN, info) ?: 0,
        ratioText = info,
        hint = hint,
    )
}

/** 取第一个捕获组里的整数，没有匹配或溢出返回 null。 */
private fun firstNumber(pattern: Regex, text: String): Int? =
    pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

/** "最低提现20元" / "最低提现金额20元"。 */
private val MIN_AMOUNT_PATTERN = Regex("""最低提现[^0-9]*([0-9]+)""")

/** "当前可提现金额5000元"。 */
private val AVAILABLE_PATTERN = Regex("""可提现金额[^0-9]*([0-9]+)""")
