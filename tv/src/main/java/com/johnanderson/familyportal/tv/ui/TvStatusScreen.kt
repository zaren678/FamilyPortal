package com.johnanderson.familyportal.tv.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Doorbell
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.johnanderson.familyportal.calendar.CalendarSyncState
import com.johnanderson.familyportal.core.ConnectionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class StatusKind { HOME_ASSISTANT, GOOGLE_CALENDAR, WEATHER, DOORBELL_POPUP }

internal enum class StatusLevel { OK, PENDING, PROBLEM, OFF }

internal data class StatusItem(val kind: StatusKind, val level: StatusLevel, val detail: String)

/** Everything the status screen reports on, gathered from the view model and system. */
internal data class TvStatusInput(
    val homeAssistantUrl: String,
    val homeAssistantCatalogLoaded: Boolean,
    val homeAssistantError: String?,
    val googleConfigured: Boolean,
    val googleAuthorized: Boolean,
    val calendarSync: CalendarSyncState,
    val weatherEntityId: String,
    val weatherName: String?,
    val weatherError: String?,
    val doorbellConfigured: Boolean,
    val overlayAllowed: Boolean,
    val doorbellConnection: ConnectionState,
)

internal fun tvStatusItems(input: TvStatusInput, now: Instant, zone: ZoneId): List<StatusItem> = listOf(
    homeAssistantStatus(input),
    googleStatus(input, now, zone),
    weatherStatus(input),
    doorbellStatus(input),
)

private fun homeAssistantStatus(input: TvStatusInput): StatusItem {
    val (level, detail) = when {
        input.homeAssistantUrl.isBlank() -> StatusLevel.OFF to "Not set up"
        input.homeAssistantError != null -> StatusLevel.PROBLEM to input.homeAssistantError
        input.homeAssistantCatalogLoaded -> StatusLevel.OK to input.homeAssistantUrl.removePrefix("http://")
        else -> StatusLevel.PENDING to "Checking…"
    }
    return StatusItem(StatusKind.HOME_ASSISTANT, level, detail)
}

private fun googleStatus(input: TvStatusInput, now: Instant, zone: ZoneId): StatusItem {
    val sync = input.calendarSync
    val lastSuccess = sync.lastSuccess
    val (level, detail) = when {
        !input.googleConfigured -> StatusLevel.OFF to "Not configured in this build"
        !input.googleAuthorized -> StatusLevel.OFF to "Not signed in"
        sync.error != null -> StatusLevel.PROBLEM to "Sync failed: ${sync.error}"
        sync.syncing -> StatusLevel.PENDING to "Syncing…"
        lastSuccess != null -> StatusLevel.OK to "Synced ${syncTimeLabel(lastSuccess, now, zone)}"
        else -> StatusLevel.PENDING to "Waiting for the first sync"
    }
    return StatusItem(StatusKind.GOOGLE_CALENDAR, level, detail)
}

private fun weatherStatus(input: TvStatusInput): StatusItem {
    val (level, detail) = when {
        input.weatherEntityId.isBlank() -> StatusLevel.OFF to "Not set up"
        input.weatherError != null -> StatusLevel.PROBLEM to input.weatherError
        input.weatherName != null -> StatusLevel.OK to input.weatherName
        else -> StatusLevel.PENDING to "Loading…"
    }
    return StatusItem(StatusKind.WEATHER, level, detail)
}

private fun doorbellStatus(input: TvStatusInput): StatusItem {
    val (level, detail) = when {
        !input.doorbellConfigured -> StatusLevel.OFF to "Off"
        !input.overlayAllowed -> StatusLevel.PROBLEM to OVERLAY_NOT_ALLOWED
        input.doorbellConnection == ConnectionState.CONNECTED -> StatusLevel.OK to "Listening"
        input.doorbellConnection == ConnectionState.CONNECTING -> StatusLevel.PENDING to "Connecting…"
        else -> StatusLevel.PROBLEM to "Not connected to Home Assistant"
    }
    return StatusItem(StatusKind.DOORBELL_POPUP, level, detail)
}

private fun syncTimeLabel(time: Instant, now: Instant, zone: ZoneId): String {
    val local = time.atZone(zone)
    return if (local.toLocalDate() == now.atZone(zone).toLocalDate()) {
        local.format(SYNC_TIME)
    } else {
        local.format(SYNC_DATE_TIME)
    }
}

/** Shows how each connection is doing, with a way to re-check them. */
@Composable
internal fun TvStatusScreen(
    items: List<StatusItem>,
    onCheckAgain: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onDone: () -> Unit,
) {
    val initialFocus = rememberInitialFocus()
    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Column(Modifier.weight(0.35f).fillMaxHeight().padding(end = 32.dp)) {
            Text("Status", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone) { Text("Done") }
        }
        LazyColumn(
            modifier = Modifier.weight(0.65f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.kind }) { item ->
                val fixPermission = item.kind == StatusKind.DOORBELL_POPUP && item.detail == OVERLAY_NOT_ALLOWED
                ListItem(
                    selected = false,
                    onClick = { if (fixPermission) onOpenOverlaySettings() },
                    modifier = Modifier.initialFocus(initialFocus, enabled = item == items.first()),
                    leadingContent = { Icon(item.kind.icon, contentDescription = null) },
                    headlineContent = { Text(item.kind.title) },
                    supportingContent = { Text(item.detail) },
                    trailingContent = { StatusIcon(item.level) },
                )
            }
            item(key = "check-again") {
                ListItem(
                    selected = false,
                    onClick = onCheckAgain,
                    leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    headlineContent = { Text("Check again") },
                )
            }
            item(key = "about") {
                val context = LocalContext.current
                val version = remember(context) {
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
                }
                ListItem(
                    selected = false,
                    onClick = {},
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                    headlineContent = { Text("Version $version") },
                    supportingContent = { Text("${Build.MODEL} · Android ${Build.VERSION.RELEASE}") },
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(level: StatusLevel) {
    val (icon, tint) = when (level) {
        StatusLevel.OK -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.secondary
        StatusLevel.PENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        StatusLevel.PROBLEM -> Icons.Default.Warning to MaterialTheme.colorScheme.error
        StatusLevel.OFF -> return
    }
    Icon(icon, contentDescription = level.name.lowercase(), tint = tint, modifier = Modifier.size(28.dp))
}

private val StatusKind.title: String
    get() = when (this) {
        StatusKind.HOME_ASSISTANT -> "Home Assistant"
        StatusKind.GOOGLE_CALENDAR -> "Google Calendar"
        StatusKind.WEATHER -> "Weather"
        StatusKind.DOORBELL_POPUP -> "Doorbell popup"
    }

private val StatusKind.icon: ImageVector
    get() = when (this) {
        StatusKind.HOME_ASSISTANT -> Icons.Default.Home
        StatusKind.GOOGLE_CALENDAR -> Icons.Default.CalendarMonth
        StatusKind.WEATHER -> Icons.Default.WbSunny
        StatusKind.DOORBELL_POPUP -> Icons.Default.Doorbell
    }

private const val OVERLAY_NOT_ALLOWED = "\"Display over other apps\" isn't allowed"
private val SYNC_TIME = DateTimeFormatter.ofPattern("h:mm a")
private val SYNC_DATE_TIME = DateTimeFormatter.ofPattern("MMM d, h:mm a")
