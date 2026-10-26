package com.starveil.app

import android.content.Context
import com.starveil.core.common.AppearanceMode
import com.starveil.core.model.LoginMethod
import com.starveil.core.model.RegisterMethod
import com.starveil.core.model.StartupConfig
import com.starveil.feature.user.LoginChoices

/**
 * 上次生效的启动快照：皮肤号、强制登录开关、登录方式，存 SharedPreferences（同步可读）。
 *
 * 这几样都要在 Compose 首帧就拿到，而它们的正常来源都是异步的：皮肤来自 DataStore
 * 与服务端 `app_theme_ui`，其余的来自冷启动现拉的 `startup`。等它们回来再画，
 * 用户看到的就是"先按默认排一遍、再重排成后台配置的样子"。所以生效那一刻顺手存一份：
 *
 * - 皮肤号：决定窗口底色（`SplashAdActivity`）；
 * - 强制登录：配置没回来时先按上次的值决定锁不锁屏，避免闪一帧游客首页；
 * - 登录方式：决定登录页先排哪一套按钮，避免「设备一键登录」晚一步才冒出来。
 *
 * 存的都是**生效值**，不是用户的选择值：用户设过皮肤时那份就是他的选择，
 * 没设过时就是上次服务端下发的值。两种情况冷启动都能直接用。
 */
internal object SplashSkinStore {

    private const val PREFS = "starveil_splash"
    private const val KEY_SKIN = "skin_code"
    private const val KEY_FORCE_LOGIN = "force_login"
    private const val KEY_LOGIN_MODES = "login_modes"
    private const val KEY_LOGIN_DEFAULT = "login_default"
    private const val KEY_REGISTER_MODES = "register_modes"
    private const val KEY_APPEARANCE = "appearance_mode"

    fun read(context: Context): String = prefs(context).getString(KEY_SKIN, "").orEmpty()

    fun write(context: Context, skinCode: String) {
        prefs(context).edit().putString(KEY_SKIN, skinCode).apply()
    }

    /**
     * 明暗偏好的同步副本。
     *
     * 真正的存储是 DataStore（异步），而启动窗口与 `SplashAdActivity` 的底色
     * 必须在 Compose 之前且同步拿到，所以这里再存一份。
     */
    fun readAppearance(context: Context): AppearanceMode =
        AppearanceMode.of(prefs(context).getString(KEY_APPEARANCE, null))

    fun writeAppearance(context: Context, mode: AppearanceMode) {
        prefs(context).edit().putString(KEY_APPEARANCE, mode.code).apply()
    }

    /** 综合用户偏好与系统设置，得出这次要不要深色。 */
    fun isDark(context: Context, systemDark: Boolean): Boolean =
        when (readAppearance(context)) {
            AppearanceMode.LIGHT -> false
            AppearanceMode.DARK -> true
            AppearanceMode.SYSTEM -> systemDark
        }

    /** 上次拿到的 `force_login`：没有记录时按关闭处理，与后端缺字段的口径一致。 */
    fun readForceLogin(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_LOGIN, false)

    /**
     * 上次拿到的登录方式，合成一份 [LoginChoices]。
     *
     * 没有记录时按 [StartupConfig.DEFAULT_LOGIN_METHODS] 走，与后端缺字段时的回落一致。
     */
    fun readLoginChoices(context: Context): LoginChoices {
        val p = prefs(context)
        val methods = split(p.getString(KEY_LOGIN_MODES, null))
            .mapNotNull(LoginMethod::of)
            .toSet()
            .ifEmpty { StartupConfig.DEFAULT_LOGIN_METHODS }
        val default = LoginMethod.of(p.getString(KEY_LOGIN_DEFAULT, null).orEmpty())
            ?: LoginMethod.PASSWORD
        val register = split(p.getString(KEY_REGISTER_MODES, null))
            .mapNotNull(RegisterMethod::of)
            .toSet()
            .ifEmpty { StartupConfig.DEFAULT_REGISTER_METHODS }
        return LoginChoices(
            methods = methods,
            defaultMethod = default,
            showRegister = register.isNotEmpty(),
        )
    }

    /** 配置到手时调一次：把这次生效的三样开关同步存下来，供下次冷启动直接用。 */
    fun writeStartup(context: Context, startup: StartupConfig) {
        prefs(context).edit()
            .putBoolean(KEY_FORCE_LOGIN, startup.forceLogin)
            .putString(KEY_LOGIN_MODES, startup.loginMethods.joinToString(",") { it.code })
            .putString(KEY_LOGIN_DEFAULT, startup.loginDefaultMethod.code)
            .putString(KEY_REGISTER_MODES, startup.registerMethods.joinToString(",") { it.code })
            .apply()
    }

    private fun split(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
