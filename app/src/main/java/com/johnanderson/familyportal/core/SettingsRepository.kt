package com.johnanderson.familyportal.core

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.settingsDataStore by preferencesDataStore(name = "portal_settings")

class SettingsRepository(
    private val context: Context,
    private val secureStore: SecureStore,
    private val json: Json,
) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        preferences[SETTINGS_JSON]?.let { raw ->
            runCatching { json.decodeFromString<AppSettings>(raw) }.getOrNull()
        } ?: AppSettings()
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { preferences ->
            val current = preferences[SETTINGS_JSON]?.let {
                runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull()
            } ?: AppSettings()
            preferences[SETTINGS_JSON] = json.encodeToString(transform(current))
        }
    }

    fun setHomeAssistantToken(token: String) = secureStore.put(SecureStore.HA_TOKEN, token)
    fun homeAssistantToken(): String? = secureStore.get(SecureStore.HA_TOKEN)

    fun setCameraRtsp(cameraId: String, uri: String) =
        secureStore.put(SecureStore.rtspKey(cameraId), uri)

    fun cameraRtsp(cameraId: String): String? = secureStore.get(SecureStore.rtspKey(cameraId))

    fun setPin(pin: String) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        secureStore.put(SecureStore.PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
        secureStore.put(SecureStore.PIN_HASH, hashPin(pin, salt))
    }

    fun hasPin(): Boolean = secureStore.get(SecureStore.PIN_HASH) != null

    fun verifyPin(pin: String): Boolean {
        val salt = secureStore.get(SecureStore.PIN_SALT)?.let {
            Base64.decode(it, Base64.NO_WRAP)
        } ?: return false
        return hashPin(pin, salt) == secureStore.get(SecureStore.PIN_HASH)
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private companion object {
        val SETTINGS_JSON = stringPreferencesKey("settings_json")
    }
}
