package io.github.ccch1mneyyy.dsh_app.calendar

import java.io.File
import java.time.ZoneId

/** One writable calendar the person can choose. */
data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val writable: Boolean,
    val primary: Boolean,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id.toString(),
        "name" to name,
        "account" to account,
        "writable" to writable,
        "primary" to primary,
    )
}

/** A normalized event. All-day events use UTC midnights; timed events use [timezone]. */
data class CalendarEvent(
    val key: String,
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val timezone: String,
    val location: String?,
    val description: String?,
    val reminderMinutes: List<Int>,
)

/** An event that this App wrote, read back from Calendar Provider. */
data class OwnedCalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val key: String,
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val timezone: String,
    val location: String?,
    val description: String?,
    val reminderMinutes: List<Int>,
) {
    fun toEvent(): CalendarEvent = CalendarEvent(
        key = key,
        title = title,
        beginMs = beginMs,
        endMs = endMs,
        allDay = allDay,
        timezone = timezone,
        location = location,
        description = description,
        reminderMinutes = reminderMinutes.distinct().sorted(),
    )
}

class CalendarWriteResult(
    val ok: Boolean,
    val message: String,
    val eventId: Long? = null,
    val created: Boolean = false,
    val removed: Int = 0,
)

/** Device operations, faked by JVM tests. */
interface CalendarApi {
    fun calendars(): List<CalendarInfo>
    fun upsert(event: CalendarEvent, calendarId: Long, existingId: Long? = null): CalendarWriteResult
    fun remove(key: String, calendarId: Long?): CalendarWriteResult
    fun removeEvent(eventId: Long): CalendarWriteResult
    fun owned(scope: String?, calendarId: Long?): List<OwnedCalendarEvent>
}

/** What [CalendarCore] needs; [CalendarControl] implements it on the phone. */
interface CalendarEnv {
    fun enabled(): Boolean
    fun hasPermission(): Boolean
    fun calendars(): List<CalendarInfo>
    fun selected(): CalendarInfo?
    fun upsert(event: CalendarEvent, calendarId: Long, existingId: Long? = null): CalendarWriteResult
    fun remove(key: String, calendarId: Long?): CalendarWriteResult
    fun removeEvent(eventId: Long): CalendarWriteResult
    fun owned(scope: String?, calendarId: Long?): List<OwnedCalendarEvent>
    fun planDir(): File?
    fun zone(): ZoneId
}
