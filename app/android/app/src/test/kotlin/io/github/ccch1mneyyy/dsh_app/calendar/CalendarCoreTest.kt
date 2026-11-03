package io.github.ccch1mneyyy.dsh_app.calendar

import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CalendarCoreTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val planRoot = Files.createTempDirectory("calendar-plans").toFile()
    private val calendars = listOf(
        CalendarInfo(1, "校园", "school@example.com", writable = true, primary = true),
        CalendarInfo(2, "个人", "me@example.com", writable = true, primary = false),
        CalendarInfo(3, "只读", "readonly@example.com", writable = false, primary = false),
    )
    private var enabled = true
    private var permission = true
    private val written = mutableListOf<CalendarEvent>()
    private val removed = mutableListOf<Pair<String, Long?>>()
    private val removedIds = mutableListOf<Long>()
    private val ownedEvents = mutableListOf<OwnedCalendarEvent>()
    private var nextId = 100L
    private var upsertCalendar: Long? = null
    private val env = object : CalendarEnv {
        override fun enabled() = enabled
        override fun hasPermission() = permission
        override fun calendars() = calendars
        override fun selected() = calendars.firstOrNull { it.id == 1L && it.writable }
        override fun upsert(event: CalendarEvent, calendarId: Long, existingId: Long?): CalendarWriteResult {
            written += event
            upsertCalendar = calendarId
            val id = existingId ?: nextId++
            ownedEvents.removeAll { it.eventId == id }
            ownedEvents += OwnedCalendarEvent(
                eventId = id,
                calendarId = calendarId,
                key = event.key,
                title = event.title,
                beginMs = event.beginMs,
                endMs = event.endMs,
                allDay = event.allDay,
                timezone = event.timezone,
                location = event.location,
                description = event.description,
                reminderMinutes = event.reminderMinutes,
            )
            return CalendarWriteResult(true, "ok", eventId = id, created = existingId == null)
        }
        override fun remove(key: String, calendarId: Long?): CalendarWriteResult {
            removed += key to calendarId
            val target = ownedEvents.firstOrNull { it.key == key && (calendarId == null || it.calendarId == calendarId) }
            if (target != null) ownedEvents.remove(target)
            return CalendarWriteResult(true, "ok", removed = 1)
        }
        override fun removeEvent(eventId: Long): CalendarWriteResult {
            removedIds += eventId
            val had = ownedEvents.removeAll { it.eventId == eventId }
            return CalendarWriteResult(true, "ok", removed = if (had) 1 else 0)
        }
        override fun owned(scope: String?, calendarId: Long?): List<OwnedCalendarEvent> = ownedEvents.filter {
            (scope == null || it.key.startsWith("$scope:")) && (calendarId == null || it.calendarId == calendarId)
        }
        override fun planDir() = planRoot
        override fun zone() = this@CalendarCoreTest.zone
    }
    private val core = CalendarCore(env)

    @After fun cleanup() {
        planRoot.deleteRecursively()
    }

    @Test fun disabledAndMissingPermissionAreRefusedBeforeParsing() {
        enabled = false
        assertTrue(core.call(CalendarToolCatalog.UPSERT, mapOf("key" to "k", "title" to "t", "begin" to "2026-11-05T14:00")).isError)
        enabled = true
        permission = false
        assertTrue(core.call(CalendarToolCatalog.LIST, emptyMap()).isError)
        assertTrue(written.isEmpty())
    }

    @Test fun timedEventDefaultsToOneHourAndKeepsThePhoneZone() {
        val out = core.call(
            CalendarToolCatalog.UPSERT,
            mapOf(
                "key" to "dsh-zju:exam:2026-11-12:math",
                "title" to "高等数学",
                "begin" to "2026-11-12T14:00",
                "location" to "东1-101",
                "reminder_minutes" to listOf(1440, 120, 1440),
            ),
        )
        assertFalse(out.isError)
        val event = written.single()
        assertEquals("高等数学", event.title)
        assertEquals(1L, upsertCalendar)
        assertEquals("dsh-zju:exam:2026-11-12:math", event.key)
        assertFalse(event.allDay)
        assertEquals(zone.id, event.timezone)
        assertEquals(3_600_000L, event.endMs - event.beginMs)
        assertEquals(listOf(120, 1440), event.reminderMinutes)
        assertEquals(
            LocalDateTime.of(2026, 11, 12, 14, 0).atZone(zone).toInstant().toEpochMilli(),
            event.beginMs,
        )
    }

    @Test fun allDayEventUsesUtcMidnightsAndExclusiveEnd() {
        val out = core.call(
            CalendarToolCatalog.UPSERT,
            mapOf("key" to "dsh-zju:due:2026-11-13", "title" to "交报告", "begin" to "2026-11-13", "reminder_minutes" to listOf(1440)),
        )
        assertFalse(out.isError)
        val event = written.single()
        assertTrue(event.allDay)
        assertEquals("UTC", event.timezone)
        assertEquals(LocalDate.of(2026, 11, 13).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), event.beginMs)
        assertEquals(LocalDate.of(2026, 11, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), event.endMs)
    }

    @Test fun invalidKeyEndAndReminderAreRejected() {
        val base = mapOf("title" to "t", "begin" to "2026-11-05T14:00")
        assertTrue(core.call(CalendarToolCatalog.UPSERT, base + ("key" to "bad key")).isError)
        assertTrue(core.call(CalendarToolCatalog.UPSERT, base + ("key" to "ok") + ("end" to "2026-11-05T13:00")).isError)
        assertTrue(core.call(CalendarToolCatalog.UPSERT, base + ("key" to "ok") + ("reminder_minutes" to listOf(-1))).isError)
        assertTrue(core.call(CalendarToolCatalog.UPSERT, base + ("key" to "ok") + ("reminder_minutes" to (1..9).toList())).isError)
        assertTrue(core.call(CalendarToolCatalog.UPSERT, base + ("key" to "ok") + ("reminder_minutes" to listOf("x"))).isError)
        assertTrue(written.isEmpty())
        assertFalse(core.call(CalendarToolCatalog.UPSERT, base + ("key" to "ok") + ("reminder_minutes" to 10)).isError)
        assertEquals(listOf(10), written.single().reminderMinutes)
    }

    @Test fun listAndRemoveUseTheSelectedCalendarAndStableKey() {
        val listed = core.call(CalendarToolCatalog.LIST, emptyMap())
        assertFalse(listed.isError)
        assertTrue(listed.text.contains("校园"))
        assertFalse(listed.text.contains("只读"))
        val removedOut = core.call(CalendarToolCatalog.REMOVE, mapOf("key" to "dsh-zju:exam:1"))
        assertFalse(removedOut.isError)
        assertEquals(listOf("dsh-zju:exam:1" to null), removed)
    }

    @Test fun requestedCalendarIsUsedForUpsertAndRemove() {
        val out = core.call(
            CalendarToolCatalog.UPSERT,
            mapOf("key" to "dsh-zju:exam:2", "title" to "物理", "begin" to "2026-11-12T14:00", "calendar_id" to "2"),
        )
        assertFalse(out.isError)
        assertEquals(2L, upsertCalendar)
        val removedOut = core.call(CalendarToolCatalog.REMOVE, mapOf("key" to "dsh-zju:exam:2", "calendar_id" to "2"))
        assertFalse(removedOut.isError)
        assertEquals(listOf("dsh-zju:exam:2" to 2L), removed)
    }

    @Test fun invalidCalendarIdIsRejectedInsteadOfFallingBackToSelected() {
        val out = core.call(
            CalendarToolCatalog.REMOVE,
            mapOf("key" to "dsh-zju:exam:2", "calendar_id" to "not-a-number"),
        )
        assertTrue(out.isError)
        assertTrue(removed.isEmpty())
    }
}
