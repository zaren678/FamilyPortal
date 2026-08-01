package com.johnanderson.familyportal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.ConnectionState
import com.johnanderson.familyportal.ha.DiscoveredHomeAssistant
import com.johnanderson.familyportal.ha.HomeAssistantCameraChoice
import com.johnanderson.familyportal.ha.HomeAssistantCatalog
import com.johnanderson.familyportal.ha.logicalCameras

@Composable
fun HomeAssistantSetupSection(
    settings: AppSettings,
    discovered: List<DiscoveredHomeAssistant>,
    catalog: HomeAssistantCatalog?,
    connectionState: ConnectionState,
    busy: Boolean,
    error: String?,
    onStartDiscovery: () -> Unit,
    onAuthorize: (String) -> Unit,
    onRefreshEntities: () -> Unit,
    onSelectSensor: (String) -> Unit,
    onSelectWeather: (String) -> Unit,
    onAddCamera: (HomeAssistantCameraChoice, Boolean) -> Unit,
    onManualSave: (String, String, String) -> Unit,
) {
    var manualExpanded by remember { mutableStateOf(false) }
    var showSensorPicker by remember { mutableStateOf(false) }
    var showWeatherPicker by remember { mutableStateOf(false) }
    var showCameraPicker by remember { mutableStateOf(false) }
    var manualUrl by remember(settings.homeAssistantUrl) { mutableStateOf(settings.homeAssistantUrl) }
    var manualToken by remember { mutableStateOf("") }
    var manualSensor by remember(settings.doorbellSensorEntityId) {
        mutableStateOf(settings.doorbellSensorEntityId)
    }
    val logicalCameras = remember(catalog) { catalog?.logicalCameras().orEmpty() }
    LaunchedEffect(Unit) { onStartDiscovery() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Home Assistant", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            IconButton(onClick = onStartDiscovery) { Icon(Icons.Default.Refresh, "Discover again") }
        }
        if (discovered.isEmpty() && settings.homeAssistantUrl.isBlank()) {
            Text("Searching your network for Home Assistant…")
        }
        discovered.forEach { server ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Home, null)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(server.locationName ?: server.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(server.url, server.version?.let { "Home Assistant $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (settings.homeAssistantUrl == server.url && catalog != null) {
                    Icon(Icons.Default.Check, "Connected", tint = MaterialTheme.colorScheme.secondary)
                } else {
                    Button(onClick = { onAuthorize(server.url) }) { Text("Connect") }
                }
            }
            HorizontalDivider()
        }
        if (settings.homeAssistantUrl.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Status: ${connectionState.name.lowercase()}",
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onRefreshEntities, enabled = !busy) {
                    Icon(Icons.Default.Refresh, null)
                    Text(" Test and refresh")
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        catalog?.let { loaded ->
            val selectedSensor = loaded.personSensors.firstOrNull {
                it.entityId == settings.doorbellSensorEntityId
            }
            PickerSummaryRow(
                title = "Doorbell trigger",
                value = selectedSensor?.name ?: settings.doorbellSensorEntityId.ifBlank { "Not selected" },
                detail = selectedSensor?.entityId,
                buttonText = if (settings.doorbellSensorEntityId.isBlank()) "Choose" else "Change",
                enabled = loaded.personSensors.isNotEmpty(),
                onClick = { showSensorPicker = true },
            )
            val selectedWeather = loaded.weather.firstOrNull {
                it.entityId == settings.weatherEntityId
            }
            PickerSummaryRow(
                title = "Weather",
                value = selectedWeather?.name ?: settings.weatherEntityId.ifBlank { "Not selected" },
                detail = selectedWeather?.entityId,
                buttonText = if (settings.weatherEntityId.isBlank()) "Choose" else "Change",
                enabled = loaded.weather.isNotEmpty(),
                onClick = { showWeatherPicker = true },
            )
            PickerSummaryRow(
                title = "Cameras",
                value = "${settings.cameras.size} configured",
                detail = "${logicalCameras.size} available from Home Assistant",
                buttonText = "Add cameras",
                enabled = logicalCameras.isNotEmpty(),
                onClick = { showCameraPicker = true },
            )
        }

        TextButton(onClick = { manualExpanded = !manualExpanded }) {
            Text(if (manualExpanded) "Hide manual setup" else "Manual setup")
        }
        if (manualExpanded) {
            OutlinedTextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                label = { Text("Server URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = manualToken,
                onValueChange = { manualToken = it },
                label = { Text("Long-lived token") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = manualSensor,
                onValueChange = { manualSensor = it },
                label = { Text("Person sensor entity") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = {
                    onManualSave(manualUrl, manualToken, manualSensor)
                    manualToken = ""
                },
                enabled = manualUrl.isNotBlank() && (manualToken.isNotBlank() || settings.homeAssistantUrl.isNotBlank()),
            ) { Text("Save and test") }
        }
    }

    if (showSensorPicker && catalog != null) {
        HomeAssistantEntityPickerDialog(
            title = "Choose doorbell trigger",
            entities = catalog.personSensors,
            selectedEntityId = settings.doorbellSensorEntityId,
            emptyText = "No matching person sensors",
            onDismiss = { showSensorPicker = false },
            onSelect = { entity ->
                onSelectSensor(entity.entityId)
                showSensorPicker = false
            },
        )
    }

    if (showWeatherPicker && catalog != null) {
        HomeAssistantEntityPickerDialog(
            title = "Choose weather entity",
            entities = catalog.weather,
            selectedEntityId = settings.weatherEntityId,
            emptyText = "No weather entities found",
            onDismiss = { showWeatherPicker = false },
            onSelect = { entity ->
                onSelectWeather(entity.entityId)
                showWeatherPicker = false
            },
        )
    }

    if (showCameraPicker && catalog != null) {
        HomeAssistantCameraPickerDialog(
            cameras = logicalCameras,
            configuredEntityIds = settings.cameras.flatMap { camera ->
                listOf(camera.entityId, camera.previewEntityId)
            }.filter(String::isNotBlank).toSet(),
            firstCameraIsDoorbell = settings.cameras.none { it.isDoorbell },
            onDismiss = { showCameraPicker = false },
            onAdd = onAddCamera,
        )
    }
}

@Composable
private fun PickerSummaryRow(
    title: String,
    value: String,
    detail: String?,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(buttonText) }
    }
}
