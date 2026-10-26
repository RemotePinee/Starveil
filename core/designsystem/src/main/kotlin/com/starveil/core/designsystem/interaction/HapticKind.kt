package com.starveil.core.designsystem.interaction

/**
 * 触感强度。只有三档，够用且不吵：
 * - [Tick]：轻点，切显隐、播放暂停、切 tab 这类"按一下"的操作
 * - [Confirm]：确认型，切集、选倍速、收藏这类"改状态"的操作
 * - [Reject]：失败，发送失败、播放失败这类需要用户注意的结果
 */
enum class HapticKind { Tick, Confirm, Reject }
