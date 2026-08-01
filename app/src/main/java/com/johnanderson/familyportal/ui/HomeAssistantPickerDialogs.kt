package com.johnanderson.familyportal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.johnanderson.familyportal.ha.HomeAssistantCameraChoice
import com.johnanderson.familyportal.ha.HomeAssistantEntityChoice

@Composable
internal fun HomeAssistantEntityPickerDialog(
    title: String,
    entities: List<HomeAssistantEntityChoice>,
    selectedEntityId: String,
    emptyText: String,
    onDismiss: () -> Unit,
    onSelect: (HomeAssistantEntityChoice) -> Unit,
    clearLabel: String? = null,
    onClear: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val filtered = entities.filter { entity ->
        query.isBlank() || entity.name.contains(query, ignoreCase = true) ||
            entity.entityId.contains(query, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(emptyText)
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(filtered, key = HomeAssistantEntityChoice::entityId) { entity ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(entity.name)
                                    Text(entity.entityId, style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { onSelect(entity) }) {
                                    if (entity.entityId == selectedEntityId) Icon(Icons.Default.Check, null)
                                    Text(if (entity.entityId == selectedEntityId) " Selected" else "Select")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (clearLabel != null && onClear != null) {
                TextButton(onClick = onClear) { Text(clearLabel) }
            }
        },
    )
}

@Composable
internal fun HomeAssistantCameraPickerDialog(
    cameras: List<HomeAssistantCameraChoice>,
    configuredEntityIds: Set<String>,
    firstCameraIsDoorbell: Boolean,
    onDismiss: () -> Unit,
    onAdd: (HomeAssistantCameraChoice, Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var addedEntityIds by remember { mutableStateOf(emptySet<String>()) }
    val filtered = cameras.filter { camera ->
        query.isBlank() || camera.main.name.contains(query, ignoreCase = true) ||
            camera.main.entityId.contains(query, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Home Assistant cameras") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search cameras") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text("No matching cameras")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(filtered, key = { it.main.entityId }) { camera ->
                            val added = camera.main.entityId in configuredEntityIds ||
                                camera.main.entityId in addedEntityIds
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(camera.main.name)
                                    Text("Main: ${camera.main.entityId}", style = MaterialTheme.typography.bodySmall)
                                    camera.preview?.let {
                                        Text("Preview: ${it.entityId}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        val doorbell = firstCameraIsDoorbell && addedEntityIds.isEmpty()
                                        onAdd(camera, doorbell)
                                        addedEntityIds = addedEntityIds + camera.main.entityId
                                    },
                                    enabled = !added,
                                ) { Text(if (added) "Added" else "Add") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
