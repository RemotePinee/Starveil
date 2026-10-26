package com.starveil.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.starveil.core.common.AppearanceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 皮肤与明暗选择。
 *
 * 规则：**本地选择优先级最高**。用户选过皮肤后，服务端 `app_theme_ui` 只当默认值，
 * 后台再改也不会覆盖用户的选择。用户选「跟随服务端」时存空串，回到后端下发值。
 *
 * 明暗同理：默认跟随系统，用户在设置里选定后以本地为准。
 */
interface ThemePreference {

    /** 当前本地选择；空串表示跟随服务端。 */
    val skinCode: Flow<String>

    /** 保存本地选择；传空串表示恢复到"跟随服务端"。 */
    suspend fun select(code: String)

    /** 明暗偏好；默认跟随系统（接口给默认实现，其它模块的 fake 不必跟着改）。 */
    val appearance: Flow<AppearanceMode> get() = flowOf(AppearanceMode.SYSTEM)

    /** 保存明暗偏好。 */
    suspend fun selectAppearance(mode: AppearanceMode) = Unit
}

class DataStoreThemePreference(
    private val dataStore: DataStore<Preferences>,
) : ThemePreference {

    override val skinCode: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SKIN_OVERRIDE].orEmpty()
    }

    override val appearance: Flow<AppearanceMode> = dataStore.data.map { prefs ->
        AppearanceMode.of(prefs[KEY_APPEARANCE])
    }

    override suspend fun select(code: String) {
        dataStore.edit { prefs -> prefs[KEY_SKIN_OVERRIDE] = code.trim() }
    }

    override suspend fun selectAppearance(mode: AppearanceMode) {
        dataStore.edit { prefs -> prefs[KEY_APPEARANCE] = mode.code }
    }

    private companion object {
        val KEY_SKIN_OVERRIDE = stringPreferencesKey("skin_override")
        val KEY_APPEARANCE = stringPreferencesKey("appearance_mode")
    }
}
