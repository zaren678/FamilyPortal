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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Doorbell
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.johnanderson.familyportal.calendar.CalendarSourceEntity
import com.johnanderson.familyportal.calendar.GoogleDeviceAuthorization
import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.ha.DiscoveredHomeAssistant
import com.johnanderson.familyportal.ha.HomeAssistantCameraChoice
import com.johnanderson.familyportal.ha.HomeAssistantCatalog
import com.johnanderson.familyportal.ha.configuredFor
import com.johnanderson.familyportal.ha.logicalCameras

/**
 * TV setup, driven entirely by the remote: Google signs in with a code entered on a
 * phone, and Home Assistant is found on the network and logged into once.
 */
@Composable
fun TvSetupScreen(
    settings: AppSettings,
    calendarSources: List<CalendarSourceEntity>,
    calendarListError: String?,
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
    onStopDiscovery: () -> Unit,
    onConnectHomeAssistant: (String) -> Unit,
    onAddCamera: (HomeAssistantCameraChoice) -> Unit,
    onRemoveCamera: (String) -> Unit,
    onSetCameraAudio: (String, Boolean) -> Unit,
    onSelectWeather: (String) -> Unit,
    overlayPermissionGranted: Boolean,
    onSelectDoorbellSensor: (String) -> Unit,
    onSelectDoorbellCamera: (String) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onTestDoorbellPopup: () -> Unit,
    onOpenStatus: () -> Unit,
    onDone: () -> Unit,
) {
    var showAddressDialog by remember { mutableStateOf(false) }
    // Keyed on sign-in so the first calendar takes focus after the code is approved.
    val initialFocus = rememberInitialFocus(googleAuthorized)
    DisposableEffect(Unit) {
        onStartDiscovery()
        onDispose { onStopDiscovery() }
    }
    LaunchedEffect(googleAuthorized, calendarSources.isEmpty()) {
        if (googleAuthorized && calendarSources.isEmpty()) onRefreshCalendars()
    }
    val logicalCameras = remember(homeAssistantCatalog) {
        homeAssistantCatalog?.logicalCameras().orEmpty()
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Column(Modifier.weight(0.35f).fillMaxHeight().padding(end = 32.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone) { Text("Done") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenStatus) {
                Icon(Icons.Default.MonitorHeart, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Status")
            }
        }
        LazyColumn(
            modifier = Modifier.weight(0.65f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sectionHeader("Google Calendar", Icons.Default.CalendarMonth)
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
                        modifier = Modifier.initialFocus(initialFocus),
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                        headlineContent = { Text("Sign in with Google") },
                        supportingContent = { Text("Enter a code on your phone") },
                    )
                }
                else -> {
                    if (calendarSources.isEmpty()) {
                        if (calendarListError == null) {
                            message("Loading calendars…")
                        } else {
                            errorMessage("Couldn't load calendars: $calendarListError")
                            item(key = "calendars-retry") {
                                ListItem(
                                    selected = false,
                                    onClick = onRefreshCalendars,
                                    modifier = Modifier.initialFocus(initialFocus),
                                    leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    headlineContent = { Text("Try again") },
                                )
                            }
                        }
                    }
                    itemsIndexed(calendarSources, key = { _, calendar -> "calendar-${calendar.id}" }) { index, calendar ->
                        ListItem(
                            selected = false,
                            onClick = { onCalendarSelected(calendar.id, !calendar.selected) },
                            modifier = Modifier.initialFocus(initialFocus, enabled = index == 0),
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
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                            headlineContent = { Text("Sign out") },
                        )
                    }
                }
            }
            googleAuthError?.takeIf { !googleAuthorized }?.let { errorMessage(it) }

            sectionHeader("Home Assistant", Icons.Default.Home)
            val connected = settings.homeAssistantUrl.isNotBlank() && homeAssistantCatalog != null
            if (connected) message(settings.homeAssistantUrl, icon = Icons.Default.CheckCircle)
            val servers = discoveredHomeAssistants.distinctBy { it.url }.filterNot { connected && it.url == settings.homeAssistantUrl }
            if (servers.isEmpty() && !connected) message("Searching your network…")
            items(servers, key = { server -> "ha-${server.url}" }) { server ->
                ListItem(
                    selected = false,
                    onClick = { onConnectHomeAssistant(server.url) },
                    enabled = !homeAssistantBusy,
                    headlineContent = { Text(server.locationName ?: server.name) },
                    supportingContent = {
                        Text(listOfNotNull(server.url, server.version?.let { "Home Assistant $it" }).joinToString(" · "))
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = "Connect") },
                )
            }
            item(key = "ha-search-again") {
                ListItem(
                    selected = false,
                    onClick = {
                        onStopDiscovery()
                        onStartDiscovery()
                    },
                    enabled = !homeAssistantBusy,
                    leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    headlineContent = { Text("Search again") },
                )
            }
            item(key = "ha-manual") {
                ListItem(
                    selected = false,
                    onClick = { showAddressDialog = true },
                    enabled = !homeAssistantBusy,
                    leadingContent = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                    headlineContent = { Text("Enter address") },
                )
            }
            if (homeAssistantBusy) message("Connecting…")
            homeAssistantError?.let { errorMessage(it) }

            homeAssistantCatalog?.let { catalog ->
                sectionHeader("Cameras", Icons.Default.Videocam)
                if (logicalCameras.isEmpty()) message("No cameras found in Home Assistant")
                items(logicalCameras, key = { choice -> "camera-${choice.main.entityId}" }) { choice ->
                    val configured = settings.cameras.configuredFor(choice)
                    ListItem(
                        selected = false,
                        onClick = {
                            if (configured != null) onRemoveCamera(configured.id) else onAddCamera(choice)
                        },
                        headlineContent = { Text(choice.main.name) },
                        trailingContent = { Switch(checked = configured != null, onCheckedChange = null) },
                    )
                }
                if (settings.cameras.isNotEmpty()) {
                    subheader("Sound in full screen", Icons.AutoMirrored.Filled.VolumeUp)
                    items(settings.cameras, key = { camera -> "audio-${camera.id}" }) { camera ->
                        ListItem(
                            selected = false,
                            onClick = { onSetCameraAudio(camera.id, !camera.hasAudio) },
                            headlineContent = { Text(camera.name) },
                            trailingContent = { Switch(checked = camera.hasAudio, onCheckedChange = null) },
                        )
                    }
                }

                if (catalog.weather.isNotEmpty()) {
                    sectionHeader("Weather", Icons.Default.WbSunny)
                    items(catalog.weather, key = { entity -> "weather-${entity.entityId}" }) { entity ->
                        ListItem(
                            selected = entity.entityId == settings.weatherEntityId,
                            onClick = { onSelectWeather(entity.entityId) },
                            headlineContent = { Text(entity.name) },
                        )
                    }
                }

                doorbellPopupSection(
                    settings = settings,
                    catalog = catalog,
                    overlayPermissionGranted = overlayPermissionGranted,
                    onSelectDoorbellSensor = onSelectDoorbellSensor,
                    onSelectDoorbellCamera = onSelectDoorbellCamera,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onTestDoorbellPopup = onTestDoorbellPopup,
                )
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

private fun LazyListScope.doorbellPopupSection(
    settings: AppSettings,
    catalog: HomeAssistantCatalog,
    overlayPermissionGranted: Boolean,
    onSelectDoorbellSensor: (String) -> Unit,
    onSelectDoorbellCamera: (String) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onTestDoorbellPopup: () -> Unit,
) {
    sectionHeader("Doorbell popup", Icons.Default.Doorbell)
    message("Shows the doorbell camera over whatever's playing.")
    subheader("Sensor")
    item(key = "doorbell-off") {
        ListItem(
            selected = settings.doorbellSensorEntityId.isBlank(),
            onClick = { onSelectDoorbellSensor("") },
            leadingContent = { Icon(Icons.Default.NotificationsOff, contentDescription = null) },
            headlineContent = { Text("Off") },
        )
    }
    if (catalog.personSensors.isEmpty()) message("No person sensors found in Home Assistant")
    items(catalog.personSensors, key = { sensor -> "doorbell-sensor-${sensor.entityId}" }) { sensor ->
        ListItem(
            selected = sensor.entityId == settings.doorbellSensorEntityId,
            onClick = { onSelectDoorbellSensor(sensor.entityId) },
            headlineContent = { Text(sensor.name) },
            supportingContent = { Text(sensor.entityId) },
        )
    }
    if (settings.doorbellSensorEntityId.isBlank()) return

    subheader("Camera")
    if (settings.cameras.isEmpty()) message("Turn on a camera above first")
    items(settings.cameras, key = { camera -> "doorbell-camera-${camera.id}" }) { camera ->
        ListItem(
            selected = camera.isDoorbell,
            onClick = { onSelectDoorbellCamera(camera.id) },
            headlineContent = { Text(camera.name) },
        )
    }
    item(key = "doorbell-permission") {
        ListItem(
            selected = false,
            onClick = onOpenOverlaySettings,
            headlineContent = { Text("Display over other apps") },
            supportingContent = if (overlayPermissionGranted) null else {
                { Text("Required: select, then allow") }
            },
            trailingContent = { Switch(checked = overlayPermissionGranted, onCheckedChange = null) },
        )
    }
    item(key = "doorbell-test") {
        ListItem(
            selected = false,
            onClick = onTestDoorbellPopup,
            enabled = overlayPermissionGranted,
            leadingContent = { Icon(Icons.Default.PictureInPicture, contentDescription = null) },
            headlineContent = { Text("Test popup") },
            supportingContent = { Text("15 seconds") },
        )
    }
}

private fun LazyListScope.subheader(title: String, icon: ImageVector? = null) {
    item(key = "subheader-$title") {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.sectionHeader(title: String, icon: ImageVector) {
    item(key = "header-$title") {
        Row(
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun LazyListScope.message(text: String, icon: ImageVector? = null) {
    item {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    authorization.verificationUri.removePrefix("https://").removePrefix("www."),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(authorization.userCode, style = MaterialTheme.typography.displayMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Waiting…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                modifier = Modifier.initialFocus(rememberInitialFocus()),
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
