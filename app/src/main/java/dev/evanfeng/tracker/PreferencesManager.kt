package dev.evanfeng.tracker

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.Preferences.Key
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    object Keys {
        val NAME = stringPreferencesKey("name")
        val ENDPOINT = stringPreferencesKey("endpoint")
        val INTERVAL = intPreferencesKey("interval")
        val CF_ACCESS_CLIENT_SECRET = stringPreferencesKey("cf_access_client_secret")
        val CF_ACCESS_CLIENT_ID = stringPreferencesKey("cf_access_client_id")
        val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
    }

    suspend fun initializeDefaultPreferences() {
        savePreference(Keys.IS_FIRST_LAUNCH, true)

        val isFirstLaunch = getPreferenceFlow(Keys.IS_FIRST_LAUNCH, true).first()
        if (isFirstLaunch) {
            savePreference(Keys.NAME, Build.MODEL)
            savePreference(Keys.INTERVAL, 60)
            savePreference(Keys.ENDPOINT, "")
            savePreference(Keys.CF_ACCESS_CLIENT_SECRET, "")
            savePreference(Keys.CF_ACCESS_CLIENT_ID, "")
            savePreference(Keys.IS_FIRST_LAUNCH, false)
        }
    }
}