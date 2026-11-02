package io.github.ccch1mneyyy.dsh_app.calendar

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
    fun upsert(event: CalendarEvent, calendarId: Long): CalendarWriteResult
    fun remove(key: String, calendarId: Long?): CalendarWriteResult
}

/** What [CalendarCore] needs; [CalendarControl] implements it on the phone. */
interface CalendarEnv {
    fun enabled(): Boolean
    fun hasPermission(): Boolean
    fun calendars(): List<CalendarInfo>
    fun selected(): CalendarInfo?
    fun upsert(event: CalendarEvent, calendarId: Long): CalendarWriteResult
    fun remove(key: String, calendarId: Long?): CalendarWriteResult
    fun zone(): ZoneId
}
