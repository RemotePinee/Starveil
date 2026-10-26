package com.starveil.core.model

/**
 * 解析器配置，来自后端 player_info。
 *
 * 四个字段都是「逗号分隔的字符串数组」，解析在 :core:network 的 mapper 里完成：
 * - parseTargets ← parse2，多个解析站，逐个尝试
 * - directLinkPatterns ← features，命中即视为直链，跳过解析
 * - headerPatterns ← issethead，命中才注入 headers
 * - headers ← headers，形如 "User-Agent=>xxx,Referer=>yyy"
 *
 * 注意：后端只透传字符串，语义完全由客户端约定。
 */
data class PlayerInfo(
    val parseTargets: List<String>,
    val directLinkPatterns: List<String>,
    val headerPatterns: List<String>,
    val headers: Map<String, String>,
)
