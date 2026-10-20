package com.johnanderson.familyportal.calendar

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "calendar_sources")
data class CalendarSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val selected: Boolean,
    val primaryCalendar: Boolean,
)

@Entity(
    tableName = "calendar_events",
    primaryKeys = ["calendarId", "eventId"],
)
data class CalendarEventEntity(
    val calendarId: String,
    val eventId: String,
    val title: String,
    val location: String?,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val allDay: Boolean,
    val color: String,
)

@Dao
abstract class CalendarDao {
    @Query("SELECT * FROM calendar_sources ORDER BY primaryCalendar DESC, name COLLATE NOCASE")
    abstract fun observeSources(): Flow<List<CalendarSourceEntity>>

    @Query("SELECT * FROM calendar_sources ORDER BY primaryCalendar DESC, name COLLATE NOCASE")
    abstract suspend fun getSources(): List<CalendarSourceEntity>

    @Query("SELECT * FROM calendar_sources WHERE selected = 1")
    abstract suspend fun getSelectedSources(): List<CalendarSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSources(sources: List<CalendarSourceEntity>)

    @Query("UPDATE calendar_sources SET selected = :selected WHERE id = :calendarId")
    abstract suspend fun setSelected(calendarId: String, selected: Boolean)

    @Query(
        "SELECT e.* FROM calendar_events e " +
            "INNER JOIN calendar_sources c ON e.calendarId = c.id " +
            "WHERE c.selected = 1 AND e.startEpochMillis < :endExclusive " +
            "AND e.endEpochMillis > :startInclusive " +
            "ORDER BY e.startEpochMillis, e.endEpochMillis",
    )
    abstract fun observeEvents(startInclusive: Long, endExclusive: Long): Flow<List<CalendarEventEntity>>

    @Query(
        "DELETE FROM calendar_events WHERE calendarId = :calendarId " +
            "AND startEpochMillis < :endExclusive AND endEpochMillis > :startInclusive",
    )
    abstract suspend fun deleteWindow(calendarId: String, startInclusive: Long, endExclusive: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertEvents(events: List<CalendarEventEntity>)

    @Transaction
    open suspend fun replaceWindow(
        calendarId: String,
        startInclusive: Long,
        endExclusive: Long,
        events: List<CalendarEventEntity>,
    ) {
        deleteWindow(calendarId, startInclusive, endExclusive)
        upsertEvents(events)
    }
}

@Database(
    entities = [CalendarSourceEntity::class, CalendarEventEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class FamilyPortalDatabase : RoomDatabase() {
    abstract fun calendarDao(): CalendarDao
}
