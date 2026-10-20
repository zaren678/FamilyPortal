package com.johnanderson.familyportal.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.johnanderson.familyportal.calendar.CalendarSourceEntity
import com.johnanderson.familyportal.calendar.GoogleDeviceAuthorization
import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.ha.DiscoveredHomeAssistant
import com.johnanderson.familyportal.ha.HomeAssistantCameraChoice
import com.johnanderson.familyportal.ha.HomeAssistantCatalog
import com.johnanderson.familyportal.ha.logicalCameras

/**
 * TV setup, driven entirely by the remote: Google signs in with a code entered on a
 * phone, and Home Assistant is found on the network and logged into once.
 */
@Composable
fun TvSetupScreen(
    settings: AppSettings,
    calendarSources: List<CalendarSourceEntity>,
    googleConfigured: Boolean,
    googleAuthorized: Boolean,
    googleDeviceAuthorization: GoogleDeviceAuthorization?,
    googleAuthBusy: Boolean,
    googleAuthError: String?,
    discoveredHomeAssistants: List<DiscoveredHomeAssistant>,
    homeAssistantCatalog: HomeAssistantCatalog?,
    homeAssistantBusy: Boolean,
    homeAssistantError: String?,
    onGoogleSignIn: () -> Unit,
    onGoogleSignOut: () -> Unit,
    onRefreshCalendars: () -> Unit,
    onCalendarSelected: (String, Boolean) -> Unit,
    onStartDiscovery: () -> Unit,
    onConnectHomeAssistant: (String) -> Unit,
    onAddCamera: (HomeAssistantCameraChoice) -> Unit,
    onRemoveCamera: (String) -> Unit,
    onSelectWeather: (String) -> Unit,
    onDone: () -> Unit,
) {
    var showAddressDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onStartDiscovery() }
    LaunchedEffect(googleAuthorized, calendarSources.isEmpty()) {
        if (googleAuthorized && calendarSources.isEmpty()) onRefreshCalendars()
    }
    val logicalCameras = remember(homeAssistantCatalog) {
        homeAssistantCatalog?.logicalCameras().orEmpty()
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Column(Modifier.weight(0.35f).fillMaxHeight().padding(end = 32.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                "This TV keeps its own settings. Sign-ins are approved on your phone, " +
                    "and changes here don't affect the Portal.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone) { Text("Done") }
        }
        LazyColumn(
            modifier = Modifier.weight(0.65f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sectionHeader("Google Calendar")
            when {
                !googleConfigured -> message(
                    "Set GOOGLE_DEVICE_OAUTH_CLIENT_ID and GOOGLE_DEVICE_OAUTH_CLIENT_SECRET " +
                        "in local.properties and rebuild.",
                )
                googleDeviceAuthorization != null -> item(key = "google-code") {
                    DeviceCodePanel(googleDeviceAuthorization)
                }
                !googleAuthorized -> item(key = "google-sign-in") {
                    ListItem(
                        selected = false,
                        onClick = onGoogleSignIn,
                        enabled = !googleAuthBusy,
                        modifier = Modifier.initialFocus(true),
                        headlineContent = { Text("Sign in with Google") },
                        supportingContent = { Text("You'll get a code to enter on your phone") },
                    )
                }
                else -> {
                    if (calendarSources.isEmpty()) message("Loading your calendars…")
                    itemsIndexed(calendarSources, key = { _, calendar -> "calendar-${calendar.id}" }) { index, calendar ->
                        ListItem(
                            selected = false,
                            onClick = { onCalendarSelected(calendar.id, !calendar.selected) },
                            modifier = Modifier.initialFocus(index == 0),
                            headlineContent = { Text(calendar.name) },
                            supportingContent = if (calendar.primaryCalendar) {
                                { Text("Primary") }
                            } else {
                                null
                            },
                            trailingContent = { Switch(checked = calendar.selected, onCheckedChange = null) },
                        )
                    }
                    item(key = "google-sign-out") {
                        ListItem(
                            selected = false,
                            onClick = onGoogleSignOut,
                            headlineContent = { Text("Sign out of Google") },
                        )
                    }
                }
            }
            googleAuthError?.takeIf { !googleAuthorized }?.let { errorMessage(it) }

            sectionHeader("Home Assistant")
            val connected = settings.homeAssistantUrl.isNotBlank() && homeAssistantCatalog != null
            if (connected) message("Connected to ${settings.homeAssistantUrl}")
            val servers = discoveredHomeAssistants.distinctBy { it.url }.filterNot { connected && it.url == settings.homeAssistantUrl }
            if (servers.isEmpty() && !connected) message("Searching your network for Home Assistant…")
            items(servers, key = { server -> "ha-${server.url}" }) { server ->
                ListItem(
                    selected = false,
                    onClick = { onConnectHomeAssistant(server.url) },
                    enabled = !homeAssistantBusy,
                    headlineContent = { Text(server.locationName ?: server.name) },
                    supportingContent = {
                        Text(listOfNotNull(server.url, server.version?.let { "Home Assistant $it" }).joinToString(" · "))
                    },
                    trailingContent = { Text("Connect") },
                )
            }
            item(key = "ha-manual") {
                ListItem(
                    selected = false,
                    onClick = { showAddressDialog = true },
                    enabled = !homeAssistantBusy,
                    headlineContent = { Text("Enter address manually") },
                )
            }
            if (homeAssistantBusy) message("Working…")
            homeAssistantError?.let { errorMessage(it) }

            homeAssistantCatalog?.let { catalog ->
                sectionHeader("Cameras")
                if (logicalCameras.isEmpty()) message("No cameras found in Home Assistant")
                items(logicalCameras, key = { choice -> "camera-${choice.main.entityId}" }) { choice ->
                    val configured = settings.cameras.configuredFor(choice)
                    ListItem(
                        selected = false,
                        onClick = {
                            if (configured != null) onRemoveCamera(configured.id) else onAddCamera(choice)
                        },
                        headlineContent = { Text(choice.main.name) },
                        supportingContent = { Text(choice.main.entityId) },
                        trailingContent = { Switch(checked = configured != null, onCheckedChange = null) },
                    )
                }

                if (catalog.weather.isNotEmpty()) {
                    sectionHeader("Weather")
                    items(catalog.weather, key = { entity -> "weather-${entity.entityId}" }) { entity ->
                        ListItem(
                            selected = entity.entityId == settings.weatherEntityId,
                            onClick = { onSelectWeather(entity.entityId) },
                            headlineContent = { Text(entity.name) },
                            supportingContent = { Text(entity.entityId) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showAddressDialog) {
        HomeAssistantAddressDialog(
            onDismiss = { showAddressDialog = false },
            onConnect = { address ->
                showAddressDialog = false
                onConnectHomeAssistant(address)
            },
        )
    }
}

/** The configured camera matching a Home Assistant camera by main or preview entity, if any. */
internal fun List<CameraConfig>.configuredFor(choice: HomeAssistantCameraChoice): CameraConfig? {
    val choiceEntityIds = setOfNotNull(choice.main.entityId, choice.preview?.entityId)
    return firstOrNull { camera ->
        setOf(camera.entityId, camera.previewEntityId).filter(String::isNotBlank).any(choiceEntityIds::contains)
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item(key = "header-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
    }
}

private fun LazyListScope.message(text: String) {
    item {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun LazyListScope.errorMessage(text: String) {
    item {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DeviceCodePanel(authorization: GoogleDeviceAuthorization) {
    Surface(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("On your phone or computer, open")
            Text(
                authorization.verificationUri,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("and enter this code:")
            Text(authorization.userCode, style = MaterialTheme.typography.displayMedium)
            Text(
                "Waiting for approval…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeAssistantAddressDialog(
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Home Assistant address") },
        text = {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { androidx.compose.material3.Text("e.g. homeassistant.local:8123") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) onConnect(address) }),
                modifier = Modifier.initialFocus(true),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConnect(address) }, enabled = address.isNotBlank()) {
                androidx.compose.material3.Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Cancel") }
        },
    )
}
