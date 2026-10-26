package com.starveil.core.network.mapper

import com.starveil.core.model.AdSession
import com.starveil.core.network.dto.AdSessionDto

/**
 * 观看会话 DTO → 领域模型。
 *
 * `seconds` 负数或缺失视为 0：调用方据此回落广告位配置的默认时长，
 * 不让一个离谱数字把"继续"按钮永久锁死或直接放开。
 */
fun AdSessionDto.toDomain(): AdSession = AdSession(
    token = token,
    seconds = seconds.coerceAtLeast(0),
    videoUrl = videoUrl,
)
