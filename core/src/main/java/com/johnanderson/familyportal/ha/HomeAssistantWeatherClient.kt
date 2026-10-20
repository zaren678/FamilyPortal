package com.johnanderson.familyportal.ha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.math.roundToInt

data class WeatherForecastPoint(
    val dateTime: String?,
    val condition: String?,
    val temperature: Double?,
    val lowTemperature: Double?,
    val precipitationProbability: Int?,
)

data class WeatherSnapshot(
    val entityId: String,
    val name: String,
    val condition: String,
    val temperature: Double?,
    val temperatureUnit: String,
    val humidity: Int?,
    val nextSunrise: String?,
    val nextSunset: String?,
    val hourlyForecast: List<WeatherForecastPoint>,
    val dailyForecast: List<WeatherForecastPoint>,
) {
    val highTemperature: Double? get() = dailyForecast.firstOrNull()?.temperature
    val lowTemperature: Double? get() = dailyForecast.firstOrNull()?.lowTemperature
}

class HomeAssistantWeatherClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val authManager: HomeAssistantAuthManager,
) {
    suspend fun load(baseUrl: String, entityId: String): WeatherSnapshot = withContext(Dispatchers.IO) {
        val token = authManager.accessToken()
            ?: throw IOException("Home Assistant authorization is required")
        val root = execute(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/states/$entityId")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build(),
        )
        val attributes = root["attributes"]?.jsonObject ?: JsonObject(emptyMap())
        val hourlyForecast = runCatching {
            loadForecast(baseUrl, entityId, token, "hourly")
        }.getOrDefault(emptyList())
        val dailyForecast = runCatching {
            loadForecast(baseUrl, entityId, token, "daily")
        }.getOrDefault(emptyList())
        val sunAttributes = runCatching {
            loadStateAttributes(baseUrl, SUN_ENTITY_ID, token)
        }.getOrNull()
        WeatherSnapshot(
            entityId = entityId,
            name = attributes.string("friendly_name") ?: entityId,
            condition = root.string("state") ?: "unknown",
            temperature = attributes.double("temperature"),
            temperatureUnit = attributes.string("temperature_unit").orEmpty(),
            humidity = attributes.int("humidity"),
            nextSunrise = sunAttributes?.string("next_rising"),
            nextSunset = sunAttributes?.string("next_setting"),
            hourlyForecast = hourlyForecast.take(HOURLY_FORECAST_LIMIT),
            dailyForecast = dailyForecast.take(DAILY_FORECAST_LIMIT),
        )
    }

    private fun loadStateAttributes(
        baseUrl: String,
        entityId: String,
        token: String,
    ): JsonObject {
        val root = execute(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/states/$entityId")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build(),
        )
        return root["attributes"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun loadForecast(
        baseUrl: String,
        entityId: String,
        token: String,
        type: String,
    ): List<WeatherForecastPoint> {
        val body = buildJsonObject {
            put("type", type)
            put("entity_id", entityId)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val root = execute(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/services/weather/get_forecasts?return_response")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(body)
                .build(),
        )
        return root["service_response"]
            ?.jsonObject
            ?.get(entityId)
            ?.jsonObject
            ?.get("forecast")
            ?.let { it as? JsonArray }
            .orEmpty()
            .mapNotNull { element ->
                val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                WeatherForecastPoint(
                    dateTime = item.string("datetime"),
                    condition = item.string("condition"),
                    temperature = item.double("temperature"),
                    lowTemperature = item.double("templow"),
                    precipitationProbability = item.int("precipitation_probability"),
                )
            }
    }

    private fun execute(request: Request): JsonObject = httpClient.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IOException("Home Assistant weather request failed (${response.code})")
        }
        runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IOException("Home Assistant returned invalid weather data", it) }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.double(key: String): Double? =
        get(key)?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.int(key: String): Int? =
        get(key)?.jsonPrimitive?.let { value ->
            value.intOrNull ?: value.doubleOrNull?.roundToInt()
        }

    private companion object {
        const val HOURLY_FORECAST_LIMIT = 6
        const val DAILY_FORECAST_LIMIT = 5
        const val SUN_ENTITY_ID = "sun.sun"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
