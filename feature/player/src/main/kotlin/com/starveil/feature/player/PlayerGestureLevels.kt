package com.starveil.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import kotlin.math.roundToInt

/** 系统没给播放器窗口亮度（-1）时的起点，与系统默认中位一致。 */
internal const val DEFAULT_BRIGHTNESS = 0.5f

/** `WindowManager.LayoutParams.screenBrightness` 的"跟随系统"值（BRIGHTNESS_OVERRIDE_NONE）。 */
private const val FOLLOW_SYSTEM_BRIGHTNESS = -1f

/**
 * 亮度（Window）与媒体音量（AudioManager）的读写。
 *
 * 只读当前值、只改本页窗口的 screenBrightness 与媒体流音量，不申请任何权限，
 * 也不动系统全局亮度（ GestureView 走的是 WRITE_SETTINGS，属于过度授权）。
 */
internal class GestureLevels(context: Context) {

    private val window = context.findActivity()?.window
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0

    /**
     * 进页面前宿主窗口的原始亮度；-1 表示"跟随系统"。
     *
     * 必须在这里记下来：亮度是 Window 的属性，退出页面时不会自己复原，
     * 不记录就只能把下一个页面也留成调过之后的样子。
     */
    private val originalBrightness: Float = window?.attributes?.screenBrightness ?: FOLLOW_SYSTEM_BRIGHTNESS

    val brightness: Float
        get() = window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: DEFAULT_BRIGHTNESS

    val volume: Float
        get() = if (maxVolume <= 0) {
            0f
        } else {
            (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) / maxVolume.toFloat()
        }

    fun setBrightness(value: Float) {
        val target = window ?: return
        target.attributes = target.attributes.also { it.screenBrightness = value }
    }

    /** 离页还原：页面销毁时调用一次，把窗口亮度放回 [originalBrightness]。 */
    fun restoreBrightness() {
        val target = window ?: return
        target.attributes = target.attributes.also { it.screenBrightness = originalBrightness }
    }

    fun setVolume(value: Float) {
        val manager = audio ?: return
        if (maxVolume <= 0) return
        val index = (value * maxVolume).roundToInt().coerceIn(0, maxVolume)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }
}

/** 播放页可能挂在任意 Context 下，向上找 Activity 才能拿到 window。 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
