package com.johnanderson.familyportal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.johnanderson.familyportal.calendar.CalendarEventEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun EventDetailDialog(
    event: CalendarEventEntity,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(6.dp)
                        .height(44.dp)
                        .background(parseColor(event.color)),
                )
                Text(
                    event.title,
                    modifier = Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
        text = {
            Column {
                Text(
                    event.detailDateTimeLabel(),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                    ),
                )
                event.location?.takeIf { it.isNotBlank() }?.let { location ->
                    Spacer(Modifier.height(14.dp))
                    Text("Location", style = MaterialTheme.typography.labelLarge)
                    Text(location, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(14.dp))
                Text("Calendar", style = MaterialTheme.typography.labelLarge)
                Text(event.calendarId, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun CalendarEventEntity.detailDateTimeLabel(): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(startEpochMillis).atZone(zone)
    val end = Instant.ofEpochMilli(endEpochMillis).atZone(zone)
    if (allDay) {
        val inclusiveEnd = Instant.ofEpochMilli(
            (endEpochMillis - 1).coerceAtLeast(startEpochMillis),
        ).atZone(zone)
        val dateLabel = if (start.toLocalDate() == inclusiveEnd.toLocalDate()) {
            start.format(DETAIL_DATE)
        } else {
            "${start.format(DETAIL_DATE)} - ${inclusiveEnd.format(DETAIL_DATE)}"
        }
        return "$dateLabel\nAll day"
    }
    return if (start.toLocalDate() == end.toLocalDate()) {
        "${start.format(DETAIL_DATE)}\n${start.format(EVENT_TIME)} - ${end.format(EVENT_TIME)}"
    } else {
        "${start.format(DETAIL_DATE)} at ${start.format(EVENT_TIME)}\n" +
            "${end.format(DETAIL_DATE)} at ${end.format(EVENT_TIME)}"
    }
}

private val EVENT_TIME = DateTimeFormatter.ofPattern("h:mm a")
private val DETAIL_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d")
