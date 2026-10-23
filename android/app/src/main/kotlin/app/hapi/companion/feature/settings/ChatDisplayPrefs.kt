package app.hapi.companion.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Local display choices scoped to a Hub and session, independent of agent configuration. */
class ChatDisplayPrefs(private val dataStore: DataStore<Preferences>) {
    fun showActivity(hubUrl: String, sessionId: String) = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it[key(hubUrl, sessionId)] ?: false }
        .distinctUntilChanged()

    suspend fun setShowActivity(hubUrl: String, sessionId: String, enabled: Boolean) {
        dataStore.edit { it[key(hubUrl, sessionId)] = enabled }
    }

    private fun key(hubUrl: String, sessionId: String) =
        booleanPreferencesKey("chat_activity_session:${hubUrl.length}:$hubUrl:$sessionId")
}
