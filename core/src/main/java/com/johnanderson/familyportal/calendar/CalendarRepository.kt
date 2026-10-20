package com.johnanderson.familyportal.calendar

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class CalendarSyncState(
    val syncing: Boolean = false,
    val lastSuccess: Instant? = null,
    val error: String? = null,
)

class CalendarRepository(
    private val dao: CalendarDao,
    private val client: GoogleCalendarClient,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val _syncState = MutableStateFlow(CalendarSyncState())
    private val syncMutex = Mutex()
    val syncState: StateFlow<CalendarSyncState> = _syncState.asStateFlow()
    val sources: Flow<List<CalendarSourceEntity>> = dao.observeSources()

    fun events(start: Instant, end: Instant): Flow<List<CalendarEventEntity>> =
        dao.observeEvents(start.toEpochMilli(), end.toEpochMilli())

    suspend fun setCalendarSelected(calendarId: String, selected: Boolean) =
        dao.setSelected(calendarId, selected)

    suspend fun refreshCalendarList() = withContext(Dispatchers.IO) {
        val previous = dao.getSources().associateBy { it.id }
        val sources = client.listCalendars().map { calendar ->
            CalendarSourceEntity(
                id = calendar.id,
                name = calendar.summary,
                color = calendar.backgroundColor ?: "#607D8B",
                selected = previous[calendar.id]?.selected ?: calendar.primary,
                primaryCalendar = calendar.primary,
            )
        }
        dao.upsertSources(sources)
    }

    suspend fun syncNow(now: Instant = Instant.now()): Result<Unit> {
        if (!syncMutex.tryLock()) return Result.success(Unit)
        _syncState.value = _syncState.value.copy(syncing = true, error = null)
        return try {
            runCatching {
                withContext(Dispatchers.IO) {
                    refreshCalendarList()
                    val start = now.minusSeconds(30L * 24 * 60 * 60)
                    val end = now.plusSeconds(180L * 24 * 60 * 60)
                    dao.getSelectedSources().forEach { source ->
                        val events = client.listEvents(source.id, start, end).mapNotNull { event ->
                            event.toEntity(source, zoneId)
                        }
                        dao.replaceWindow(
                            source.id,
                            start.toEpochMilli(),
                            end.toEpochMilli(),
                            events,
                        )
                    }
                }
                _syncState.value = CalendarSyncState(lastSuccess = Instant.now())
            }.onFailure { error ->
                Log.e("FamilyPortalCalendar", "Calendar sync failed", error)
                _syncState.value = _syncState.value.copy(syncing = false, error = error.message)
            }
        } finally {
            syncMutex.unlock()
        }
    }
}

internal fun GoogleEvent.toEntity(
    source: CalendarSourceEntity,
    defaultZone: ZoneId,
): CalendarEventEntity? {
    val allDay = start.date != null
    val startInstant = start.toInstant(defaultZone) ?: return null
    val endInstant = end.toInstant(defaultZone) ?: return null
    return CalendarEventEntity(
        calendarId = source.id,
        eventId = id,
        title = summary?.takeIf(String::isNotBlank) ?: "Untitled event",
        location = location,
        startEpochMillis = startInstant.toEpochMilli(),
        endEpochMillis = endInstant.toEpochMilli(),
        allDay = allDay,
        color = source.color,
    )
}

private fun GoogleEventTime.toInstant(defaultZone: ZoneId): Instant? = when {
    date != null -> LocalDate.parse(date).atStartOfDay(defaultZone).toInstant()
    dateTime != null -> runCatching { OffsetDateTime.parse(dateTime).toInstant() }
        .recoverCatching {
            ZonedDateTime.parse(dateTime).toInstant()
        }
        .getOrNull()
    else -> null
}
