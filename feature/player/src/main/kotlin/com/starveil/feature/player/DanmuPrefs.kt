package com.starveil.feature.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable

/**
 * 弹幕显示偏好（本地状态，不进 ViewModel）。
 *
 * 收成一个对象有两个原因：
 * 1. 播放页的 `PlayerScreen` 有圈复杂度上限（detekt 10），散着写会超；
 * 2. 行数与显示区域有约束关系（4/6 行在半屏里放不下），约束只能集中在这里。
 *
 * 全部字段可保存：转屏/进程回收后弹幕设置不丢。
 */
@Stable
internal class DanmuPrefs(
    enabled: Boolean = true,
    fontScale: Float = DANMU_FONT_DEFAULT,
    colorIndex: Int = 0,
    laneCount: Int = DANMU_DEFAULT_LANES,
    areaFull: Boolean = false,
    opacity: Float = DANMU_OPACITY_FULL,
) {
    var enabled by mutableStateOf(enabled)

    var fontScale by mutableFloatStateOf(fontScale)

    var colorIndex by mutableIntStateOf(colorIndex)

    var laneCount by mutableIntStateOf(laneCount)
        private set

    var areaFull by mutableStateOf(areaFull)
        private set

    var opacity by mutableFloatStateOf(opacity)

    /** 显示区域占画面比例：勾了"全屏"才用满。 */
    val areaFraction: Float get() = if (areaFull) 1f else DANMU_DEFAULT_AREA

    /** 选 4/6 行时自动切全屏：半屏放不下那几行，不联动的话用户会以为没生效。 */
    fun selectLaneCount(lanes: Int) {
        laneCount = lanes
        if (lanes > DANMU_DEFAULT_LANES) areaFull = true
    }

    fun selectArea(full: Boolean) {
        // 只切区域，不动用户选的行数：行数由"区域放得下几行"在绘制层裁剪。
        // 在这里把 4/6 行偷偷改成 3 行，用户看到的就是"切换没反应"
        areaFull = full
    }

    /** 面板需要的样式快照。 */
    fun style(): DanmuStyle = DanmuStyle(
        colorIndex = colorIndex,
        fontScale = fontScale,
        layout = DanmuLayoutPrefs(laneCount = laneCount, areaFull = areaFull, opacity = opacity),
    )

    /** 面板需要的回调集合。 */
    fun events(): DanmuEvents = DanmuEvents(
        look = DanmuLookEvents(
            onColorSelected = { colorIndex = it },
            onFontScaleSelected = { fontScale = it },
        ),
        layout = DanmuLayoutEvents(
            onLaneCountSelected = ::selectLaneCount,
            onAreaSelected = ::selectArea,
            onOpacitySelected = { opacity = it },
        ),
    )

    internal companion object {
        val Saver = listSaver<DanmuPrefs, Any>(
            save = { prefs ->
                listOf(
                    prefs.enabled,
                    prefs.fontScale,
                    prefs.colorIndex,
                    prefs.laneCount,
                    prefs.areaFull,
                    prefs.opacity,
                )
            },
            restore = { values ->
                DanmuPrefs(
                    enabled = values[0] as Boolean,
                    fontScale = values[1] as Float,
                    colorIndex = values[2] as Int,
                    laneCount = values[3] as Int,
                    areaFull = values[4] as Boolean,
                    opacity = values[5] as Float,
                )
            },
        )
    }
}

/** 可保存的弹幕偏好。 */
@Composable
internal fun rememberDanmuPrefs(): DanmuPrefs = rememberSaveable(saver = DanmuPrefs.Saver) { DanmuPrefs() }
