package io.github.ccch1mneyyy.dsh_app.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract

/**
 * CalendarContract adapter. Events are tagged with CUSTOM_APP_PACKAGE + CUSTOM_APP_URI so an
 * upsert can find the event it wrote before, without reading or touching unrelated events.
 */
class AndroidCalendarStore(private val context: Context) : CalendarApi {
    private val resolver get() = context.contentResolver
    private val packageName get() = context.packageName

    override fun calendars(): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val out = ArrayList<CalendarInfo>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(CalendarContract.Calendars._ID)
            val nameCol = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountCol = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            val accessCol = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val primaryCol = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            if (idCol < 0 || accessCol < 0) return@use
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val account = if (accountCol >= 0) cursor.getString(accountCol).orEmpty() else ""
                val name = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: account.takeIf { it.isNotBlank() }
                    ?: "日历 $id"
                val level = cursor.getInt(accessCol)
                out += CalendarInfo(
                    id = id,
                    name = name,
                    account = account,
                    writable = level >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    primary = primaryCol >= 0 && cursor.getInt(primaryCol) == 1,
                )
            }
        }
        return out.sortedWith(compareByDescending<CalendarInfo> { it.primary }.thenBy { it.name })
    }

    override fun upsert(event: CalendarEvent, calendarId: Long): CalendarWriteResult {
        val existing = find(event.key)
        return if (existing == null) insert(event, calendarId) else update(existing, event, calendarId)
    }

    override fun remove(key: String, calendarId: Long?): CalendarWriteResult {
        val id = find(key, calendarId) ?: return CalendarWriteResult(true, "没有找到带这个 key 的 DSH 日历事件，可能已经删掉了。", removed = 0)
        resolver.delete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID}=?",
            arrayOf(id.toString()),
        )
        val removed = resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        return CalendarWriteResult(
            ok = true,
            message = if (removed > 0) "已从手机日历删除这条 DSH 事件。" else "事件已经不在了。",
            removed = if (removed > 0) 1 else 0,
        )
    }

    private fun insert(event: CalendarEvent, calendarId: Long): CalendarWriteResult {
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values(event, calendarId))
            ?: return CalendarWriteResult(false, "手机日历没有接受这条事件。请检查是否选了一个可写日历。")
        val id = ContentUris.parseId(uri)
        replaceReminders(id, event.reminderMinutes)
        return CalendarWriteResult(
            ok = true,
            message = "已写入手机日历「${event.title}」。",
            eventId = id,
            created = true,
        )
    }

    private fun update(id: Long, event: CalendarEvent, calendarId: Long): CalendarWriteResult {
        val changed = resolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            values(event, calendarId),
            null,
            null,
        )
        if (changed <= 0) return CalendarWriteResult(false, "没有更新成功；这条事件可能已被删除，稍后再试。")
        replaceReminders(id, event.reminderMinutes)
        return CalendarWriteResult(
            ok = true,
            message = "已更新手机日历「${event.title}」。",
            eventId = id,
            created = false,
        )
    }

    private fun values(event: CalendarEvent, calendarId: Long): ContentValues = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, event.title)
        put(CalendarContract.Events.DTSTART, event.beginMs)
        put(CalendarContract.Events.DTEND, event.endMs)
        put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
        put(CalendarContract.Events.EVENT_TIMEZONE, event.timezone)
        put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
        put(CalendarContract.Events.HAS_ALARM, if (event.reminderMinutes.isEmpty()) 0 else 1)
        put(CalendarContract.Events.CUSTOM_APP_PACKAGE, packageName)
        put(CalendarContract.Events.CUSTOM_APP_URI, customUri(event.key))
        event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        event.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
    }

    private fun find(key: String, calendarId: Long? = null): Long? {
        val calendar = if (calendarId == null) "" else " AND ${CalendarContract.Events.CALENDAR_ID}=?"
        val selection = "${CalendarContract.Events.CUSTOM_APP_PACKAGE}=? AND ${CalendarContract.Events.CUSTOM_APP_URI}=?$calendar"
        val args = if (calendarId == null) arrayOf(packageName, customUri(key)) else arrayOf(packageName, customUri(key), calendarId.toString())
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            selection,
            args,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun replaceReminders(eventId: Long, minutes: List<Int>) {
        resolver.delete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString()),
        )
        for (minute in minutes) {
            resolver.insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, minute)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                },
            )
        }
    }

    private fun customUri(key: String): String = "dsh-calendar://event/${Uri.encode(key)}"
}
