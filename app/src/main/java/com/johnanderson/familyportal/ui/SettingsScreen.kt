package com.johnanderson.familyportal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.johnanderson.familyportal.calendar.CalendarSourceEntity
import com.johnanderson.familyportal.calendar.GoogleDeviceAuthorization
import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.core.ConnectionState
import com.johnanderson.familyportal.ha.DiscoveredHomeAssistant
import com.johnanderson.familyportal.ha.HomeAssistantCameraChoice
import com.johnanderson.familyportal.ha.HomeAssistantEntityChoice
import com.johnanderson.familyportal.ha.HomeAssistantCatalog
import com.johnanderson.familyportal.ha.isLikelyCameraSubstream

@Composable
fun SettingsScreen(
    settings: AppSettings,
    calendarSources: List<CalendarSourceEntity>,
    googleConfigured: Boolean,
    googleAuthorized: Boolean,
    googleDeviceAuthorization: GoogleDeviceAuthorization?,
    googleAuthBusy: Boolean,
    googleAuthError: String?,
    homeAssistantState: ConnectionState,
    discoveredHomeAssistants: List<DiscoveredHomeAssistant>,
    homeAssistantCatalog: HomeAssistantCatalog?,
    homeAssistantSetupBusy: Boolean,
    homeAssistantSetupError: String?,
    hasPin: Boolean,
    onGoogleSignIn: () -> Unit,
    onGoogleSignOut: () -> Unit,
    onRefreshCalendars: () -> Unit,
    onCalendarSelected: (String, Boolean) -> Unit,
    onStartHomeAssistantDiscovery: () -> Unit,
    onAuthorizeHomeAssistant: (String) -> Unit,
    onRefreshHomeAssistantEntities: () -> Unit,
    onSelectDoorbellSensor: (String) -> Unit,
    onSelectWeather: (String) -> Unit,
    onAddDiscoveredCamera: (HomeAssistantCameraChoice, Boolean) -> Unit,
    onSaveHomeAssistant: (String, String, String) -> Unit,
    onSaveCamera: (String?, String, String, String, String, Boolean, Boolean) -> Unit,
    onDeleteCamera: (String) -> Unit,
    onSaveDoorbell: (Int) -> Unit,
    onSetPin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var alertSeconds by rememberSaveable(settings.alertDurationSeconds) {
        mutableStateOf(settings.alertDurationSeconds.toString())
    }
    var editingCamera by remember { mutableStateOf<CameraConfig?>(null) }
    var showCameraDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 20.dp))
        }
        item { SectionTitle("Google Calendar") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (googleAuthorized) {
                    Button(onClick = onGoogleSignOut) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null)
                        Text(" Sign out")
                    }
                    IconButton(onClick = onRefreshCalendars) { Icon(Icons.Default.Refresh, "Refresh calendars") }
                } else {
                    Button(onClick = onGoogleSignIn, enabled = googleConfigured && !googleAuthBusy) {
                        Icon(Icons.AutoMirrored.Filled.Login, null)
                        Text(if (googleAuthBusy) " Waiting for Google" else " Sign in with Google")
                    }
                }
                if (!googleConfigured) {
                    Text(
                        "Set the Google device OAuth client ID and secret in local.properties",
                        modifier = Modifier.padding(start = 12.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        if (!googleAuthorized && googleAuthError != null) {
            item { Text(googleAuthError, color = MaterialTheme.colorScheme.error) }
        }
        if (googleAuthorized) {
            items(calendarSources, key = CalendarSourceEntity::id) { calendar ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = calendar.selected,
                        onCheckedChange = { onCalendarSelected(calendar.id, it) },
                    )
                    Text(calendar.name, modifier = Modifier.weight(1f))
                    if (calendar.primaryCalendar) Text("Primary", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            HorizontalDivider()
            HomeAssistantSetupSection(
                settings = settings,
                discovered = discoveredHomeAssistants,
                catalog = homeAssistantCatalog,
                connectionState = homeAssistantState,
                busy = homeAssistantSetupBusy,
                error = homeAssistantSetupError,
                onStartDiscovery = onStartHomeAssistantDiscovery,
                onAuthorize = onAuthorizeHomeAssistant,
                onRefreshEntities = onRefreshHomeAssistantEntities,
                onSelectSensor = onSelectDoorbellSensor,
                onSelectWeather = onSelectWeather,
                onAddCamera = onAddDiscoveredCamera,
                onManualSave = onSaveHomeAssistant,
            )
        }
        item { HorizontalDivider(); SectionTitle("Configured cameras") }
        items(settings.cameras, key = CameraConfig::id) { camera ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).padding(vertical = 6.dp),
                ) {
                    TextButton(onClick = { editingCamera = camera; showCameraDialog = true }) {
                        Text(camera.name)
                    }
                    Text("Main: ${camera.entityId}", style = MaterialTheme.typography.bodySmall)
                    if (camera.previewEntityId.isNotBlank()) {
                        Text("Grid: ${camera.previewEntityId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (camera.hasAudio) Text("Audio", color = MaterialTheme.colorScheme.primary)
                if (camera.isDoorbell) Text("Doorbell", color = MaterialTheme.colorScheme.tertiary)
                IconButton(onClick = { onDeleteCamera(camera.id) }) {
                    Icon(Icons.Default.Delete, "Delete ${camera.name}")
                }
            }
        }
        item {
            OutlinedButton(onClick = { editingCamera = null; showCameraDialog = true }) {
                Icon(Icons.Default.Add, null)
                Text(" Add camera")
            }
        }
        item { HorizontalDivider(); SectionTitle("Doorbell alerts") }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = alertSeconds,
                    onValueChange = { alertSeconds = it.filter(Char::isDigit) },
                    label = { Text("After stop (seconds)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(onClick = { onSaveDoorbell(alertSeconds.toIntOrNull() ?: 30) }) {
                    Icon(Icons.Default.Save, null)
                    Text(" Save")
                }
            }
        }
        item { HorizontalDivider(); SectionTitle("Security") }
        item {
            OutlinedButton(onClick = { showPinDialog = true }) {
                Text(if (hasPin) "Change settings PIN" else "Set settings PIN")
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (googleDeviceAuthorization != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Connect Google Calendar") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("On a phone or computer, open:")
                    Text(
                        googleDeviceAuthorization.verificationUri,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("Enter this code:")
                    Text(
                        googleDeviceAuthorization.userCode,
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text("Waiting for authorization...")
                }
            },
            confirmButton = {},
        )
    }

    if (showCameraDialog) {
        CameraEditDialog(
            existing = editingCamera,
            cameraEntities = homeAssistantCatalog?.cameras.orEmpty(),
            onDismiss = { showCameraDialog = false },
            onSave = { id, name, entity, previewEntity, rtsp, hasAudio, doorbell ->
                onSaveCamera(id, name, entity, previewEntity, rtsp, hasAudio, doorbell)
                showCameraDialog = false
            },
        )
    }
    if (showPinDialog) {
        PinDialog(
            title = if (hasPin) "Change PIN" else "Set PIN",
            onDismiss = { showPinDialog = false },
            onSubmit = { onSetPin(it); showPinDialog = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun CameraEditDialog(
    existing: CameraConfig?,
    cameraEntities: List<HomeAssistantEntityChoice>,
    onDismiss: () -> Unit,
    onSave: (String?, String, String, String, String, Boolean, Boolean) -> Unit,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var entity by rememberSaveable(existing?.id) { mutableStateOf(existing?.entityId.orEmpty()) }
    var previewEntity by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.previewEntityId.orEmpty())
    }
    var rtsp by rememberSaveable(existing?.id) { mutableStateOf("") }
    var showMainPicker by remember { mutableStateOf(false) }
    var showPreviewPicker by remember { mutableStateOf(false) }
    var hasAudio by rememberSaveable(existing?.id) { mutableStateOf(existing?.hasAudio == true) }
    var doorbell by rememberSaveable(existing?.id) { mutableStateOf(existing?.isDoorbell == true) }
    if (!showMainPicker && !showPreviewPicker) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (existing == null) "Add camera" else "Edit camera") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    CameraEntitySelectionRow(
                        label = "HA camera entity",
                        entityId = entity,
                        entities = cameraEntities,
                        onChoose = { showMainPicker = true },
                    )
                    CameraEntitySelectionRow(
                        label = "HA grid/substream entity",
                        entityId = previewEntity,
                        entities = cameraEntities,
                        onChoose = { showPreviewPicker = true },
                        onClear = { previewEntity = "" },
                    )
                    OutlinedTextField(
                        rtsp,
                        { rtsp = it },
                        label = { Text(if (existing == null) "RTSP URL" else "New RTSP URL (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Has audio", Modifier.weight(1f))
                        Switch(hasAudio, { hasAudio = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use for doorbell alerts", Modifier.weight(1f))
                        Switch(doorbell, { doorbell = it })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && entity.isNotBlank() && (existing != null || rtsp.isNotBlank()),
                    onClick = { onSave(existing?.id, name, entity, previewEntity, rtsp, hasAudio, doorbell) },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }

    if (showMainPicker) {
        HomeAssistantEntityPickerDialog(
            title = "Choose main camera",
            entities = cameraEntities.filterNot { it.isLikelyCameraSubstream() },
            selectedEntityId = entity,
            emptyText = "No main camera entities found",
            onDismiss = { showMainPicker = false },
            onSelect = {
                entity = it.entityId
                showMainPicker = false
            },
        )
    }
    if (showPreviewPicker) {
        HomeAssistantEntityPickerDialog(
            title = "Choose grid preview",
            entities = cameraEntities.filter { it.isLikelyCameraSubstream() },
            selectedEntityId = previewEntity,
            emptyText = "No substream camera entities found",
            onDismiss = { showPreviewPicker = false },
            onSelect = {
                previewEntity = it.entityId
                showPreviewPicker = false
            },
            clearLabel = "No preview",
            onClear = {
                previewEntity = ""
                showPreviewPicker = false
            },
        )
    }
}

@Composable
private fun CameraEntitySelectionRow(
    label: String,
    entityId: String,
    entities: List<HomeAssistantEntityChoice>,
    onChoose: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val entityName = entities.firstOrNull { it.entityId == entityId }?.name
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(entityName ?: entityId.ifBlank { "Not selected" })
            if (entityName != null) Text(entityId, style = MaterialTheme.typography.bodySmall)
        }
        if (onClear != null && entityId.isNotBlank()) {
            TextButton(onClick = onClear) { Text("Clear") }
        }
        OutlinedButton(onClick = onChoose, enabled = entities.isNotEmpty()) { Text("Choose") }
    }
}

@Composable
fun PinDialog(
    title: String,
    error: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text("4–8 digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4, onClick = { onSubmit(pin) }) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
