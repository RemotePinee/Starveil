package com.starveil.app.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 弹窗调度的四条硬规则：互斥、优先级、频控、可重入。
 *
 * 这些是纯逻辑，不进 Android 环境就能跑——调度器刻意不依赖 Compose 与 Context，
 * 就是为了能在这一步把"三个弹窗会不会叠"钉死在断言里。
 */
class DialogSchedulerTest {

    private val shown = mutableMapOf<String, Long>()
    private var clock = 1_000L

    private val history = object : DialogHistory {
        override fun lastShownAt(key: String): Long = shown[key] ?: 0L
        override fun markShown(key: String, at: Long) {
            shown[key] = at
        }
    }

    private fun scheduler() = DialogScheduler(history = history, now = { clock })

    @Test
    fun `同一时刻只出一个，且按优先级出队`() {
        val subject = scheduler()
        subject.offer(DialogRequest(AppDialogKind.Message, "message:1"))
        subject.offer(DialogRequest(AppDialogKind.Consent, "consent"))

        assertEquals("consent", subject.current.value?.key)

        // 处理完最高优先级的那个，自动让位给下一个
        subject.complete(DialogRequest(AppDialogKind.Consent, "consent"))
        assertEquals("message:1", subject.current.value?.key)
    }

    @Test
    fun `同一个 key 重复提交不会堆积`() {
        val subject = scheduler()
        val request = DialogRequest(AppDialogKind.Message, "message:1")
        repeat(5) { subject.offer(request) }
        subject.complete(request)

        assertNull("撤掉之后不该还留着副本", subject.current.value)
    }

    @Test
    fun `公告弹出过一次之后不再重弹`() {
        val subject = scheduler()
        val request = DialogRequest(AppDialogKind.Message, "message:1")
        subject.offer(request)
        subject.complete(request)

        subject.offer(request)
        assertNull(subject.current.value)
    }

    @Test
    fun `强制更新每次启动都要拦`() {
        val subject = scheduler()
        val request = DialogRequest(AppDialogKind.ForceUpdate, "force:7")
        subject.offer(request)
        subject.complete(request)

        clock += 1
        subject.offer(request)
        assertEquals("用户跳过之后必须能再拦一次", "force:7", subject.current.value?.key)
    }

    @Test
    fun `可选更新同版本一天内只提醒一次`() {
        val subject = scheduler()
        val request = DialogRequest(AppDialogKind.Update, "update:7")
        subject.offer(request)
        subject.complete(request)

        clock += 60 * 60 * 1000L
        subject.offer(request)
        assertNull("一小时内不该重复提醒", subject.current.value)

        clock += 24 * 60 * 60 * 1000L
        subject.offer(request)
        assertEquals("过了冷却期要能再提", "update:7", subject.current.value?.key)
    }
}
