package com.starveil.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.starveil.core.model.Session
import kotlinx.coroutines.flow.first

/**
 * DataStore 实现。
 *
 * 只存 cookie 值，不存 domain（见 [SessionStore] 注释）。
 * 读写在 IO 调度器上由 DataStore 自己保证，这里不做额外线程切换。
 */
class PreferenceSessionStore(
    private val dataStore: DataStore<Preferences>,
) : SessionStore {

    override suspend fun load(): Session? {
        val prefs = dataStore.data.first()
        val userId = prefs[KEY_USER_ID].orEmpty()
        if (userId.isBlank()) return null
        return Session(
            userId = userId,
            userName = prefs[KEY_USER_NAME].orEmpty(),
            nickName = prefs[KEY_NICK_NAME].orEmpty(),
            groupId = prefs[KEY_GROUP_ID].orEmpty(),
            groupName = prefs[KEY_GROUP_NAME].orEmpty(),
            userCheck = prefs[KEY_USER_CHECK].orEmpty(),
            portrait = prefs[KEY_PORTRAIT].orEmpty(),
        )
    }

    override suspend fun save(session: Session) {
        dataStore.edit { prefs ->
            prefs[KEY_USER_ID] = session.userId
            prefs[KEY_USER_NAME] = session.userName
            prefs[KEY_NICK_NAME] = session.nickName
            prefs[KEY_GROUP_ID] = session.groupId
            prefs[KEY_GROUP_NAME] = session.groupName
            prefs[KEY_USER_CHECK] = session.userCheck
            prefs[KEY_PORTRAIT] = session.portrait
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    private companion object {
        val KEY_USER_ID = stringPreferencesKey("session_user_id")
        val KEY_USER_NAME = stringPreferencesKey("session_user_name")
        val KEY_NICK_NAME = stringPreferencesKey("session_user_nick_name")
        val KEY_GROUP_ID = stringPreferencesKey("session_group_id")
        val KEY_GROUP_NAME = stringPreferencesKey("session_group_name")
        val KEY_USER_CHECK = stringPreferencesKey("session_user_check")
        val KEY_PORTRAIT = stringPreferencesKey("session_user_portrait")
    }
}
