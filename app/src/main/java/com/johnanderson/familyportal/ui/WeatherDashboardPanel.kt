package com.johnanderson.familyportal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.johnanderson.familyportal.ha.WeatherForecastPoint
import com.johnanderson.familyportal.ha.WeatherSnapshot
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeatherDashboardPanel(
    weather: WeatherSnapshot?,
    error: String?,
    now: Instant,
    zone: ZoneId,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Weather", style = MaterialTheme.typography.headlineSmall)
        weather?.name?.let { locationName ->
            Text(
                locationName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            weather != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeatherConditionIcon(
                        condition = weather.condition,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            weather.temperature?.let { "${it.roundToInt()}${weather.temperatureUnit}" } ?: "--",
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Text(weather.condition.readableCondition(), style = MaterialTheme.typography.titleMedium)
                    }
                }
                val range = listOfNotNull(
                    weather.highTemperature?.let { "High ${it.roundToInt()}${weather.temperatureUnit}" },
                    weather.lowTemperature?.let { "Low ${it.roundToInt()}${weather.temperatureUnit}" },
                ).joinToString("  ")
                if (range.isNotBlank()) Text(range, style = MaterialTheme.typography.bodyLarge)
                weather.humidity?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WaterDrop, null, modifier = Modifier.padding(end = 6.dp))
                        Text("Humidity $it%")
                    }
                }
                val sunrise = weather.nextSunrise?.let { sunEventLabel(it, now, zone) }
                val sunset = weather.nextSunset?.let { sunEventLabel(it, now, zone) }
                if (sunrise != null || sunset != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sunrise?.let {
                            SunEvent(Icons.Default.LightMode, "Next sunrise", it)
                        }
                        sunset?.let {
                            SunEvent(Icons.Default.DarkMode, "Next sunset", it)
                        }
                    }
                }
                if (weather.hourlyForecast.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Next hours", style = MaterialTheme.typography.titleMedium)
                    ForecastStrip(
                        points = weather.hourlyForecast,
                        temperatureUnit = weather.temperatureUnit,
                        daily = false,
                    )
                }
                if (weather.dailyForecast.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Next days", style = MaterialTheme.typography.titleMedium)
                    ForecastStrip(
                        points = weather.dailyForecast,
                        temperatureUnit = weather.temperatureUnit,
                        daily = true,
                    )
                }
                error?.let {
                    Text(
                        "Last update failed: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            error != null -> Text(error, color = MaterialTheme.colorScheme.error)
            else -> Text("Choose a Home Assistant weather entity in Settings")
        }
    }
}

@Composable
private fun SunEvent(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ForecastStrip(
    points: List<WeatherForecastPoint>,
    temperatureUnit: String,
    daily: Boolean,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(points) { point ->
            Column(
                modifier = Modifier.width(if (daily) 76.dp else 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    point.forecastLabel(daily),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                WeatherConditionIcon(
                    condition = point.condition.orEmpty(),
                    modifier = Modifier.size(30.dp),
                )
                Text(
                    point.temperatureLabel(temperatureUnit, daily),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                point.precipitationProbability?.let { probability ->
                    Text(
                        "$probability%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherConditionIcon(
    condition: String,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = weatherConditionIcon(condition),
        contentDescription = condition.readableCondition(),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

internal fun weatherConditionIcon(condition: String): ImageVector = when (condition.lowercase()) {
    "clear-night" -> MdiWeatherIcons.ClearNight
    "cloudy" -> MdiWeatherIcons.Cloudy
    "exceptional", "unknown", "unavailable" -> MdiWeatherIcons.Exceptional
    "fog" -> MdiWeatherIcons.Fog
    "hail" -> MdiWeatherIcons.Hail
    "lightning" -> MdiWeatherIcons.Lightning
    "lightning-rainy" -> MdiWeatherIcons.LightningRainy
    "partlycloudy" -> MdiWeatherIcons.PartlyCloudy
    "pouring" -> MdiWeatherIcons.Pouring
    "rainy" -> MdiWeatherIcons.Rainy
    "snowy" -> MdiWeatherIcons.Snowy
    "snowy-rainy" -> MdiWeatherIcons.SnowyRainy
    "sunny" -> MdiWeatherIcons.Sunny
    "windy" -> MdiWeatherIcons.Windy
    "windy-variant" -> MdiWeatherIcons.WindyVariant
    else -> MdiWeatherIcons.Cloudy
}

private fun String.readableCondition(): String =
    split('-', '_').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

private fun WeatherForecastPoint.forecastLabel(daily: Boolean): String {
    val instant = forecastInstant() ?: return "--"
    return instant.atZone(ZoneId.systemDefault()).format(if (daily) FORECAST_DAY else FORECAST_HOUR)
}

private fun WeatherForecastPoint.temperatureLabel(unit: String, daily: Boolean): String {
    val high = temperature?.roundToInt()?.toString() ?: "--"
    val low = lowTemperature?.roundToInt()
    return if (daily && low != null) "$high°/$low°" else "$high$unit"
}

private fun WeatherForecastPoint.forecastInstant(): Instant? = dateTime?.let(::parseTimestamp)

internal fun sunEventLabel(value: String, now: Instant, zone: ZoneId): String? {
    val eventTime = parseTimestamp(value)?.atZone(zone) ?: return null
    val today = now.atZone(zone).toLocalDate()
    val dayLabel = when (eventTime.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> eventTime.format(FORECAST_DAY)
    }
    return "$dayLabel ${eventTime.format(SUN_TIME)}"
}

private fun parseTimestamp(value: String): Instant? =
    runCatching { Instant.parse(value) }
        .recoverCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { ZonedDateTime.parse(value).toInstant() }
        .getOrNull()

private val FORECAST_HOUR = DateTimeFormatter.ofPattern("h a")
private val FORECAST_DAY = DateTimeFormatter.ofPattern("EEE")
private val SUN_TIME = DateTimeFormatter.ofPattern("h:mm a")
