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
import com.johnanderson.familyportal.ha.HomeAssistantCatalog
import com.johnanderson.familyportal.ha.isLikelyCameraSubstream

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
    onAddCamera: (String, String, Boolean) -> Unit,
    onManualSave: (String, String, String) -> Unit,
) {
    var manualExpanded by remember { mutableStateOf(false) }
    var manualUrl by remember(settings.homeAssistantUrl) { mutableStateOf(settings.homeAssistantUrl) }
    var manualToken by remember { mutableStateOf("") }
    var manualSensor by remember(settings.doorbellSensorEntityId) {
        mutableStateOf(settings.doorbellSensorEntityId)
    }
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
            val primaryCameras = loaded.cameras.filterNot { camera ->
                camera.isLikelyCameraSubstream() ||
                    settings.cameras.any { it.previewEntityId == camera.entityId }
            }
            Text("Person sensor", style = MaterialTheme.typography.titleMedium)
            if (loaded.personSensors.isEmpty()) Text("No likely person sensors were found.")
            loaded.personSensors.forEach { sensor ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sensor.name)
                        Text(sensor.entityId, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { onSelectSensor(sensor.entityId) }) {
                        if (settings.doorbellSensorEntityId == sensor.entityId) Icon(Icons.Default.Check, null)
                        Text(if (settings.doorbellSensorEntityId == sensor.entityId) " Selected" else "Select")
                    }
                }
            }

            Text("Cameras", style = MaterialTheme.typography.titleMedium)
            if (primaryCameras.isEmpty()) Text("No camera entities were found.")
            primaryCameras.forEach { camera ->
                val alreadyAdded = settings.cameras.any { it.entityId == camera.entityId }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(camera.name)
                        Text(camera.entityId, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = { onAddCamera(camera.entityId, camera.name, settings.cameras.none { it.isDoorbell }) },
                        enabled = !alreadyAdded,
                    ) { Text(if (alreadyAdded) "Added" else "Add") }
                }
            }
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
}
