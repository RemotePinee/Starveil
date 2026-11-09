package com.starveil.app.dialog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 弹窗种类。
 *
 * 优先级按"能不能继续用"排，不是按运营重要性：
 * - [Consent] 不同意就不该放行个性化广告，必须最先处理
 * - [ForceUpdate] 旧版本接口可能已经不通，比运营消息更硬
 * - [Message] 运营内容，晚一条不影响使用
 * - [Update] 可选更新，最软
 */
internal enum class AppDialogKind(val priority: Int) {
    Consent(0),
    ForceUpdate(1),
    Message(2),
    Update(3),
}

/**
 * 一条候选弹窗。
 *
 * [key] 是频控的身份：同一个 key 在冷却期内不会再弹。
 * 用业务 id 拼出来（`message:42` / `update:7`），不要用弹窗种类，
 * 否则"第二条公告"会因为"第一条公告"弹过而被一起压掉。
 */
internal data class DialogRequest(
    val kind: AppDialogKind,
    val key: String,
)

/** 弹过的记录。抽成接口是为了让调度器能在纯 JVM 单测里跑，不依赖 Android。 */
internal interface DialogHistory {
    /** 上次弹出的时间戳；从没弹过返回 0。 */
    fun lastShownAt(key: String): Long

    /** 记下这次弹出。 */
    fun markShown(key: String, at: Long)
}

/**
 * 弹窗调度器：**整个 App 任何时刻最多一个弹窗**。
 *
 * 解决的问题：冷启动时隐私弹窗、版本更新、运营公告可能同时满足条件。
 * 三个 `Dialog` 一起挂上去会叠成三层，用户点掉最上面那层才发现下面还有两个——
 * 既像 bug，也容易误触；这也是"启动就糊一脸弹窗"的来源。
 *
 * 规则固定四条：
 * 1. **互斥**：只有 [current] 会被渲染，其余在 [pending] 里排队
 * 2. **优先级**：数字小的先出队
 * 3. **频控**：按 [DialogRequest.key] 判冷却，见 [cooldownOf]
 * 4. **可重入**：同一个 key 重复 `offer` 不会堆积（Map 去重）
 *
 * 宿主只做两件事：条件满足时 `offer`，用户关掉后 `complete`。
 * 调度器不关心弹窗长什么样，也不依赖 Compose。
 */
internal class DialogScheduler(
    private val history: DialogHistory,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val pending = LinkedHashMap<String, DialogRequest>()

    private val _current = MutableStateFlow<DialogRequest?>(null)

    /** 当前该渲染的那一个；没有时是 null。 */
    val current: StateFlow<DialogRequest?> = _current.asStateFlow()

    /**
     * 提交一个候选。
     *
     * 已经在队里、或还在冷却期内的直接忽略——宿主可以每次重组都调它，
     * 不用自己记"我是不是已经提过了"。
     */
    fun offer(request: DialogRequest) {
        if (request.key in pending) return
        if (!isDue(request)) return
        pending[request.key] = request
        publish()
    }

    /** 条件不再成立时撤回（比如版本检查发现已经没有更新了）。 */
    fun withdraw(key: String) {
        if (pending.remove(key) != null) publish()
    }

    /**
     * 用户处理完了。
     *
     * 顺手记一条历史：下一次同 key 的候选要等冷却期过才会再排队。
     * 强制更新与隐私同意由各自的 [cooldownOf] 决定是否还要再弹。
     */
    fun complete(request: DialogRequest) {
        history.markShown(request.key, now())
        pending.remove(request.key)
        publish()
    }

    private fun isDue(request: DialogRequest): Boolean {
        val last = history.lastShownAt(request.key)
        if (last == 0L) return true
        val cooldown = cooldownOf(request.kind)
        return cooldown == 0L || now() - last >= cooldown
    }

    private fun publish() {
        _current.value = pending.values.minByOrNull { it.kind.priority }
    }

    private fun cooldownOf(kind: AppDialogKind): Long = when (kind) {
        // 隐私同意是一次性的：同意了不该再问，所以标记后永不重弹
        AppDialogKind.Consent -> Long.MAX_VALUE
        // 强制更新每次启动都要拦，否则用户跳过就再也拦不住
        AppDialogKind.ForceUpdate -> 0L
        // 一条公告只弹一次，读完进消息中心
        AppDialogKind.Message -> Long.MAX_VALUE
        // 可选更新：同一个版本一天最多提醒一次
        AppDialogKind.Update -> UPDATE_COOLDOWN_MS
    }

    private companion object {
        /** 同一个版本的更新提示，24 小时内不重复。 */
        const val UPDATE_COOLDOWN_MS = 24L * 60 * 60 * 1000
    }
}
