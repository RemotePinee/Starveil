package com.starveil.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 会员体系是否开放（服务端 `member_enabled`）。
 *
 * 只影响**售卖入口**：会员页、底栏会员 Tab、我的页会员卡的按钮。
 * 历史会员的观看权益由服务端按到期时间继续放行，客户端不用这个开关做权限判断。
 *
 * 默认 true：老后端不下发该字段时保持现有行为，测试也不用逐个提供值。
 */
val LocalMemberEnabled = staticCompositionLocalOf { true }
