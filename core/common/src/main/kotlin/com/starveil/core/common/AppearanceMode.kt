package com.starveil.core.common

/**
 * 明暗外观偏好。
 *
 * 放在 `:core:common` 而不是 `:core:model`：`:core:designsystem` 只允许依赖 common，
 * 而主题层（`StarveilTheme` / 选择器）必须能读到它。
 *
 * 只决定"亮还是暗"，不参与皮肤色相：皮肤仍由后台 `app_theme_ui` 下发默认值，
 * 用户手选的那一套优先（见 `ThemePreference`）。
 */
enum class AppearanceMode(val code: String, val displayName: String) {
    /** 跟随系统（默认）。 */
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    ;

    companion object {
        fun of(code: String?): AppearanceMode =
            entries.firstOrNull { it.code == code?.trim() } ?: SYSTEM
    }
}
