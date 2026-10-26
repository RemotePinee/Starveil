package com.starveil.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import com.starveil.core.common.DetailSections

/**
 * 详情页三个区块的开关（推荐 / 评分 / 讨论）。
 *
 * 走 CompositionLocal 而不是给每个详情页组件加参数：这三个开关是横切配置，
 * 与 `LocalMemberEnabled` 同一套做法——`:app` 在导航层提供一次，页面直接读，
 * `:feature:detail` 不需要知道启动配置是怎么拉的。
 *
 * 默认值全是「显示」，未提供时行为与没有这套开关时完全一致。
 */
val LocalDetailSections = staticCompositionLocalOf { DetailSections.ALL }
