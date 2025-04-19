package dev.evanfeng.tracker

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.Preferences.Key
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PreferencesManager(private val dataStore: DataStore<Preferences>) {

    /**
     * Generic function to get a Flow for a preference value.
     * @param key The preference key.
     * @param defaultValue The default value if the preference is not set.
     */
    fun <T> getPreferenceFlow(key: Key<T>, defaultValue: T): Flow<T> {
        return dataStore.data.map { preferences ->
            preferences[key] ?: defaultValue
        }
    }

    /**
     * Generic function to save a preference value.
     * @param key The preference key.
     * @param value The value to save.
     */
    suspend fun <T> savePreference(key: Key<T>, value: T) {
        dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    val nameFlow: Flow<String> = getPreferenceFlow(Keys.NAME, "")
    val intervalFlow: Flow<Int> = getPreferenceFlow(Keys.INTERVAL, 0)

    suspend fun saveName(name: String) = savePreference(Keys.NAME, name)
    suspend fun saveInterval(interval: Int) = savePreference(Keys.INTERVAL, interval)

    object Keys {
        val NAME = androidx.datastore.preferences.core.stringPreferencesKey("name")
        val INTERVAL = androidx.datastore.preferences.core.intPreferencesKey("interval")
        val TOKEN = androidx.datastore.preferences.core.stringPreferencesKey("token")
    }
}