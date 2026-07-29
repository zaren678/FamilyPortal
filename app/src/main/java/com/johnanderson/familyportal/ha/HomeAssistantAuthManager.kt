package com.johnanderson.familyportal.ha

import android.net.Uri
import com.johnanderson.familyportal.core.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

class HomeAssistantAuthManager(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val secureStore: SecureStore,
) {
    fun authorizationUri(baseUrl: String): Uri {
        val stateBytes = ByteArray(24).also(SecureRandom()::nextBytes)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
        secureStore.put(SecureStore.HA_AUTH_STATE, state)
        secureStore.put(SecureStore.HA_AUTH_BASE_URL, baseUrl.trimEnd('/'))
        return Uri.parse(baseUrl.trimEnd('/')).buildUpon()
            .appendPath("auth")
            .appendPath("authorize")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .build()
    }

    suspend fun completeAuthorization(callback: Uri): Result<String> = runCatching {
        require(isAuthorizationCallback(callback)) { "Invalid callback" }
        val expectedState = secureStore.get(SecureStore.HA_AUTH_STATE)
        require(expectedState != null && callback.getQueryParameter("state") == expectedState) {
            "Home Assistant authorization state did not match"
        }
        callback.getQueryParameter("error")?.let { throw IOException("Home Assistant authorization failed: $it") }
        val code = callback.getQueryParameter("code") ?: throw IOException("Authorization code was missing")
        val baseUrl = secureStore.get(SecureStore.HA_AUTH_BASE_URL)
            ?: throw IOException("Home Assistant URL was missing")
        val tokens = exchange(baseUrl, "authorization_code", code = code)
        store(tokens)
        secureStore.remove(SecureStore.HA_AUTH_STATE)
        baseUrl
    }

    suspend fun accessToken(forceRefresh: Boolean = false): String? {
        val current = secureStore.get(SecureStore.HA_TOKEN) ?: return null
        val refreshToken = secureStore.get(SecureStore.HA_REFRESH_TOKEN) ?: return current
        val expiry = secureStore.get(SecureStore.HA_TOKEN_EXPIRY)?.toLongOrNull() ?: 0L
        if (!forceRefresh && System.currentTimeMillis() < expiry - 60_000L) return current
        val baseUrl = secureStore.get(SecureStore.HA_AUTH_BASE_URL) ?: return current
        val tokens = exchange(baseUrl, "refresh_token", refreshToken = refreshToken)
        store(tokens.copy(refreshToken = tokens.refreshToken ?: refreshToken))
        return tokens.accessToken
    }

    fun storeManualToken(baseUrl: String, token: String) {
        secureStore.put(SecureStore.HA_TOKEN, token)
        secureStore.put(SecureStore.HA_AUTH_BASE_URL, baseUrl.trimEnd('/'))
        secureStore.remove(SecureStore.HA_REFRESH_TOKEN)
        secureStore.remove(SecureStore.HA_TOKEN_EXPIRY)
    }

    fun cameraRtsp(cameraId: String): String? = secureStore.get(SecureStore.rtspKey(cameraId))

    private suspend fun exchange(
        baseUrl: String,
        grantType: String,
        code: String? = null,
        refreshToken: String? = null,
    ): TokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", grantType)
            .add("client_id", CLIENT_ID)
            .apply {
                code?.let { add("code", it) }
                refreshToken?.let { add("refresh_token", it) }
            }
            .build()
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/auth/token").post(form).build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Home Assistant token exchange failed (${response.code})")
            json.decodeFromString<TokenResponse>(body)
        }
    }

    private fun store(tokens: TokenResponse) {
        secureStore.put(SecureStore.HA_TOKEN, tokens.accessToken)
        tokens.refreshToken?.let { secureStore.put(SecureStore.HA_REFRESH_TOKEN, it) }
        secureStore.put(
            SecureStore.HA_TOKEN_EXPIRY,
            (System.currentTimeMillis() + tokens.expiresIn * 1_000L).toString(),
        )
    }

    @Serializable
    private data class TokenResponse(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String,
        @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String? = null,
        @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long = 1_800,
    )

    companion object {
        const val CLIENT_ID = "https://johnanderson.github.io/familyportal"
        const val REDIRECT_URI = "https://johnanderson.github.io/familyportal/auth/callback"

        fun isAuthorizationCallback(uri: Uri): Boolean =
            uri.scheme == "https" &&
                uri.host == "johnanderson.github.io" &&
                uri.path == "/familyportal/auth/callback"
    }
}
