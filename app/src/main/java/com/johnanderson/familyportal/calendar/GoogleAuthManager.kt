package com.johnanderson.familyportal.calendar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.johnanderson.familyportal.R
import com.johnanderson.familyportal.core.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.browser.BrowserMatcher
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GoogleDeviceAuthorization(
    val userCode: String,
    val verificationUri: String,
    val expiresAtMillis: Long,
)

class GoogleAuthManager(
    context: Context,
    private val secureStore: SecureStore,
    private val httpClient: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val clientId = context.getString(R.string.google_oauth_client_id)
    private val deviceClientId = context.getString(R.string.google_device_oauth_client_id)
    private val deviceClientSecret = context.getString(R.string.google_device_oauth_client_secret)
    private val authorizationService = AuthorizationService(
        context,
        AppAuthConfiguration.Builder()
            .setBrowserMatcher(BrowserMatcher { false })
            .build(),
    )
    private val serviceConfiguration = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token"),
    )

    var authState: AuthState = loadState()
        private set

    private val _authorized = MutableStateFlow(authState.isAuthorized)
    private val deviceRefreshMutex = Mutex()
    val authorized: StateFlow<Boolean> = _authorized.asStateFlow()

    val isConfigured: Boolean
        get() = !deviceClientId.startsWith("000000000000-placeholder") &&
            deviceClientSecret != "placeholder"

    val isAuthorized: Boolean
        get() = authState.isAuthorized

    suspend fun authorizeDevice(
        onCode: (GoogleDeviceAuthorization) -> Unit,
    ): Result<Unit> = runCatching {
        check(isConfigured) { "Set GOOGLE_DEVICE_OAUTH_CLIENT_ID in local.properties" }
        val session = requestDeviceCode()
        onCode(
            GoogleDeviceAuthorization(
                userCode = session.userCode,
                verificationUri = session.verificationUri,
                expiresAtMillis = System.currentTimeMillis() + session.expiresIn * 1_000L,
            ),
        )
        pollForDeviceToken(session)
    }

    private suspend fun requestDeviceCode(): DeviceCodeSession = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", deviceClientId)
            .add("scope", GOOGLE_SCOPES.joinToString(" "))
            .build()
        val request = Request.Builder().url(DEVICE_CODE_ENDPOINT).post(form).build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IOException("Google returned an invalid device authorization response")
            }
            if (!response.isSuccessful) {
                throw IOException(json.optString("error_description", "Google device authorization failed"))
            }
            DeviceCodeSession(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUri = json.optString("verification_url")
                    .ifBlank { json.getString("verification_uri") },
                expiresIn = json.getLong("expires_in"),
                intervalSeconds = json.optLong("interval", 5L),
            )
        }
    }

    private suspend fun pollForDeviceToken(session: DeviceCodeSession) {
        val deadline = System.currentTimeMillis() + session.expiresIn * 1_000L
        var intervalSeconds = session.intervalSeconds
        while (System.currentTimeMillis() < deadline) {
            delay(intervalSeconds * 1_000L)
            val result = requestDeviceToken(session.deviceCode)
            when (result.error) {
                null -> {
                    val tokenRequest = TokenRequest.Builder(serviceConfiguration, deviceClientId)
                        .setGrantType(DEVICE_GRANT_TYPE)
                        .setScopes(GOOGLE_SCOPES)
                        .setAdditionalParameters(mapOf("device_code" to session.deviceCode))
                        .build()
                    val token = TokenResponse.Builder(tokenRequest)
                        .fromResponseJsonString(result.body)
                        .build()
                    authState.update(token, null)
                    persist()
                    _authorized.value = authState.isAuthorized
                    return
                }
                "authorization_pending" -> Unit
                "slow_down" -> intervalSeconds += 5L
                "access_denied" -> throw IOException("Google authorization was denied")
                "expired_token" -> throw IOException("Google authorization code expired")
                else -> throw IOException(result.description ?: "Google authorization failed: ${result.error}")
            }
        }
        throw IOException("Google authorization code expired")
    }

    private suspend fun requestDeviceToken(deviceCode: String): DeviceTokenResult =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("client_id", deviceClientId)
                .add("client_secret", deviceClientSecret)
                .add("device_code", deviceCode)
                .add("grant_type", DEVICE_GRANT_TYPE)
                .build()
            val request = Request.Builder().url(serviceConfiguration.tokenEndpoint.toString()).post(form).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) return@use DeviceTokenResult(body)
                val json = runCatching { JSONObject(body) }.getOrNull()
                DeviceTokenResult(
                    body = body,
                    error = json?.optString("error")?.takeIf(String::isNotBlank) ?: "token_error",
                    description = json?.optString("error_description")?.takeIf(String::isNotBlank),
                )
            }
        }

    fun authorizationIntent(): Intent {
        check(isConfigured) { "Set GOOGLE_OAUTH_CLIENT_ID in local.properties" }
        val request = AuthorizationRequest.Builder(
            serviceConfiguration,
            clientId,
            ResponseTypeValues.CODE,
            redirectUri(),
        )
            .setScopes(
                "openid",
                "email",
                "https://www.googleapis.com/auth/calendar.readonly",
            )
            .setPrompt("consent")
            .setAdditionalParameters(mapOf("access_type" to "offline"))
            .build()
        secureStore.put(SecureStore.GOOGLE_AUTH_REQUEST, request.jsonSerializeString())
        val browserIntent = Intent(Intent.ACTION_VIEW, request.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val portalBrowserIntent = Intent(browserIntent).setClassName(
            PORTAL_BROWSER_PACKAGE,
            PORTAL_BROWSER_ACTIVITY,
        )
        return if (portalBrowserIntent.resolveActivity(appContext.packageManager) != null) {
            portalBrowserIntent
        } else {
            browserIntent
        }
    }

    suspend fun handleAuthorizationRedirect(callback: Uri): Result<Unit> {
        if (!isAuthorizationCallback(callback)) {
            return Result.failure(IllegalArgumentException("Invalid Google authorization callback"))
        }
        val request = secureStore.get(SecureStore.GOOGLE_AUTH_REQUEST)
            ?.let { runCatching { AuthorizationRequest.jsonDeserialize(it) }.getOrNull() }
            ?: return Result.failure(IllegalStateException("Google authorization request was missing"))
        callback.getQueryParameter("error")?.let {
            val exception = AuthorizationException.fromOAuthRedirect(callback)
            authState.update(null as AuthorizationResponse?, exception)
            persist()
            return Result.failure(exception)
        }
        val response = runCatching { AuthorizationResponse.Builder(request).fromUri(callback).build() }
            .getOrElse { return Result.failure(it) }
        if (response.state != request.state) {
            return Result.failure(IllegalStateException("Google authorization state did not match"))
        }
        authState.update(response, null)
        persist()
        return runCatching {
            suspendCancellableCoroutine { continuation ->
                authorizationService.performTokenRequest(response.createTokenExchangeRequest()) { token, error ->
                    authState.update(token, error)
                    persist()
                    _authorized.value = authState.isAuthorized
                    when {
                        continuation.isCompleted -> Unit
                        token != null -> {
                            secureStore.remove(SecureStore.GOOGLE_AUTH_REQUEST)
                            continuation.resume(Unit)
                        }
                        else -> continuation.resumeWithException(
                            error ?: IllegalStateException("Google token exchange failed"),
                        )
                    }
                }
            }
        }
    }

    suspend fun accessToken(): String {
        val deviceAuthorized = authState.lastAuthorizationResponse == null &&
            authState.lastTokenResponse?.request?.clientId == deviceClientId &&
            authState.refreshToken != null
        if (deviceAuthorized) return deviceAccessToken()

        return suspendCancellableCoroutine { continuation ->
            authState.performActionWithFreshTokens(authorizationService) { accessToken, _, exception ->
                persist()
                _authorized.value = authState.isAuthorized
                when {
                    continuation.isCompleted -> Unit
                    accessToken != null -> continuation.resume(accessToken)
                    else -> continuation.resumeWithException(
                        exception ?: IllegalStateException("Google authorization is required"),
                    )
                }
            }
        }
    }

    private suspend fun deviceAccessToken(): String = deviceRefreshMutex.withLock {
        if (!authState.needsTokenRefresh) {
            authState.accessToken?.let { return it }
        }
        val refreshToken = authState.refreshToken
            ?: throw IllegalStateException("Google authorization is required")
        val form = FormBody.Builder()
            .add("client_id", deviceClientId)
            .add("client_secret", deviceClientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", REFRESH_GRANT_TYPE)
            .build()
        val request = Request.Builder().url(serviceConfiguration.tokenEndpoint.toString()).post(form).build()
        val body = withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(responseBody).optString("error_description") }
                        .getOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?: "Google token refresh failed"
                    throw IOException(detail)
                }
                responseBody
            }
        }
        val tokenRequest = TokenRequest.Builder(serviceConfiguration, deviceClientId)
            .setGrantType(REFRESH_GRANT_TYPE)
            .setRefreshToken(refreshToken)
            .build()
        val token = TokenResponse.Builder(tokenRequest)
            .fromResponseJsonString(body)
            .build()
        authState.update(token, null)
        persist()
        _authorized.value = authState.isAuthorized
        authState.accessToken ?: throw IOException("Google token refresh returned no access token")
    }

    fun signOut() {
        authState = AuthState(serviceConfiguration)
        _authorized.value = false
        secureStore.remove(SecureStore.GOOGLE_AUTH_STATE)
        secureStore.remove(SecureStore.GOOGLE_AUTH_REQUEST)
    }

    fun close() = authorizationService.dispose()

    fun isAuthorizationCallback(uri: Uri): Boolean =
        uri.scheme == redirectUri().scheme && uri.path == redirectUri().path

    private fun redirectUri(): Uri {
        val clientPrefix = clientId.substringBefore(".apps.googleusercontent.com")
        return Uri.parse("com.googleusercontent.apps.$clientPrefix:/oauth2redirect")
    }

    private fun loadState(): AuthState = secureStore.get(SecureStore.GOOGLE_AUTH_STATE)
        ?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
        ?: AuthState(serviceConfiguration)

    private fun persist() {
        secureStore.put(SecureStore.GOOGLE_AUTH_STATE, authState.jsonSerializeString())
    }

    private data class DeviceCodeSession(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Long,
        val intervalSeconds: Long,
    )

    private data class DeviceTokenResult(
        val body: String,
        val error: String? = null,
        val description: String? = null,
    )

    companion object {
        private const val DEVICE_CODE_ENDPOINT = "https://oauth2.googleapis.com/device/code"
        private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        private const val REFRESH_GRANT_TYPE = "refresh_token"
        private val GOOGLE_SCOPES = listOf(
            "openid",
            "email",
            "https://www.googleapis.com/auth/calendar.readonly",
        )
        private const val PORTAL_BROWSER_PACKAGE = "org.chromium.chrome"
        private const val PORTAL_BROWSER_ACTIVITY =
            "org.chromium.chrome.browser.ChromeTabbedActivity"
    }
}
