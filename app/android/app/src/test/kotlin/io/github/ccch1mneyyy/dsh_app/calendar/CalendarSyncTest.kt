package io.github.ccch1mneyyy.dsh_app.calendar

import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.phone.ToolOutput
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CalendarSyncTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val planRoot = Files.createTempDirectory("calendar-sync-plans").toFile()
    private val calendars = listOf(
        CalendarInfo(1, "校园", "school@example.com", writable = true, primary = true),
        CalendarInfo(2, "旧日历", "old@example.com", writable = true, primary = false),
    )
    private var clock = 1_700_000_000_000L
    private var nextId = 1L
    private var upsertCalls = 0
    private var failWrites = false
    private val ownedEvents = mutableListOf<OwnedCalendarEvent>()
    private val env = object : CalendarEnv {
        override fun enabled() = true
        override fun hasPermission() = true
        override fun calendars() = calendars
        override fun selected() = calendars.first()
        override fun upsert(event: CalendarEvent, calendarId: Long, existingId: Long?): CalendarWriteResult {
            upsertCalls++
            if (failWrites) return CalendarWriteResult(false, "测试写入失败")
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
        override fun remove(key: String, calendarId: Long?): CalendarWriteResult = CalendarWriteResult(true, "ok")
        override fun removeEvent(eventId: Long): CalendarWriteResult {
            val had = ownedEvents.removeAll { it.eventId == eventId }
            return CalendarWriteResult(true, "ok", removed = if (had) 1 else 0)
        }
        override fun owned(scope: String?, calendarId: Long?): List<OwnedCalendarEvent> = ownedEvents.filter {
            (scope == null || it.key.startsWith("$scope:")) && (calendarId == null || it.calendarId == calendarId)
        }
        override fun planDir() = planRoot
        override fun zone() = this@CalendarSyncTest.zone
    }
    private val core = CalendarCore(env, now = { clock })
    private var planNumber = 0

    @After fun cleanup() {
        planRoot.deleteRecursively()
    }

    @Test fun dryRunCountsAndWriteAppliesWithoutDuplicates() {
        ownedEvents += owned("zju:2026-2027-1:class:old", "旧课", "2026-09-01T08:00", "2026-09-01T09:35", reminders = listOf(10))
        ownedEvents += owned("zju:2026-2027-1:exam:b", "考试", "2026-10-01T09:00", "2026-10-01T11:00", reminders = listOf(1440, 60), description = "旧")
        ownedEvents += owned("zju:2026-2027-1:due:c", "截止", "2026-10-02T09:45", "2026-10-02T10:00", reminders = listOf(1440, 120), description = "状态：未提交")
        val planId = writePlan(
            listOf(
                event("zju:2026-2027-1:class:new", "新课", "2026-09-02T08:00", "2026-09-02T09:35", reminders = listOf(10)),
                event("zju:2026-2027-1:exam:b", "考试改名", "2026-10-01T09:00", "2026-10-01T11:00", reminders = listOf(1440, 60), description = "新"),
                event("zju:2026-2027-1:due:c", "截止", "2026-10-02T09:45", "2026-10-02T10:00", reminders = listOf(1440, 120), description = "状态：未提交"),
            ),
        )
        val dry = json(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId)))
        assertEquals(true, dry["dry_run"])
        assertEquals(1, number(dry["changes"], "create"))
        assertEquals(1, number(dry["changes"], "update"))
        assertEquals(1, number(dry["changes"], "remove"))
        assertEquals(1, number(dry["changes"], "unchanged"))
        assertEquals(0, upsertCalls)

        val token = dry["token"] as String
        val written = json(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId, "dry_run" to false, "token" to token)))
        assertEquals(true, written["ok"])
        assertEquals(1, number(written["changes"], "create"))
        assertEquals(1, number(written["changes"], "update"))
        assertEquals(1, number(written["changes"], "remove"))
        assertEquals(3, ownedEvents.size)
        assertTrue(ownedEvents.none { it.key.endsWith(":class:old") })
        assertEquals("考试改名", ownedEvents.single { it.key.endsWith(":exam:b") }.title)

        val again = json(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId)))
        assertEquals(0, number(again["changes"], "create"))
        assertEquals(0, number(again["changes"], "update"))
        assertEquals(0, number(again["changes"], "remove"))
        assertEquals(3, number(again["changes"], "unchanged"))
    }

    @Test fun writeNeedsTheTokenAndTokenExpires() {
        val planId = writePlan(listOf(event("zju:2026-2027-1:class:a", "课", "2026-09-02T08:00")))
        val refused = core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId, "dry_run" to false))
        assertTrue(refused.isError)
        assertEquals(0, upsertCalls)

        val dry = json(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId)))
        val token = dry["token"] as String
        clock += CalendarCore.TOKEN_TTL_MS + 1
        val expired = core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId, "dry_run" to false, "token" to token))
        assertTrue(expired.isError)
        assertEquals(0, upsertCalls)
    }

    @Test fun tokenIsOneUseAndStaleAfterTheCalendarChanges() {
        val planId = writePlan(listOf(event("zju:2026-2027-1:class:a", "课", "2026-09-02T08:00")))
        val first = json(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId)))
        val token = first["token"] as String
        val written = core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId, "dry_run" to false, "token" to token))
        assertFalse(written.isError)
        val repeated = core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId, "dry_run" to false, "token" to token))
        assertTrue(repeated.isError)
        assertEquals(1, upsertCalls)

        val second = json(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId)))
        val secondToken = second["token"] as String
        ownedEvents += owned("zju:2026-2027-1:class:other", "用户删改后的事件", "2026-09-03T08:00", "2026-09-03T09:00")
        val stale = core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId, "dry_run" to false, "token" to secondToken))
        assertTrue(stale.isError)
        assertEquals(1, upsertCalls)
    }

    @Test fun preserveKindsAndMigrateControlOldEvents() {
        ownedEvents += owned("zju:2026-2027-1:due:d", "未交作业", "2026-10-02T09:45", "2026-10-02T10:00", reminders = listOf(1440, 120))
        ownedEvents += owned("zju:2026-2027-1:class:old", "退掉的课", "2026-09-01T08:00", "2026-09-01T09:35", calendarId = 2)
        val planId = writePlan(
            listOf(event("zju:2026-2027-1:class:new", "新课", "2026-09-02T08:00")),
            preserveKinds = listOf("due"),
        )

        val dry = json(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId)))
        assertEquals(1, number(dry["changes"], "create"))
        assertEquals(0, number(dry["changes"], "remove"))
        assertTrue((dry["warnings"] as List<*>).any { it.toString().contains("旧日历") })
        val token = dry["token"] as String
        core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId, "dry_run" to false, "token" to token))
        assertTrue(ownedEvents.any { it.key.endsWith(":due:d") })
        assertTrue(ownedEvents.any { it.key.endsWith(":class:old") })

        val migrated = json(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId, "migrate" to true)))
        assertEquals(0, number(migrated["changes"], "create"))
        assertEquals(1, number(migrated["changes"], "remove"))
        val migrateToken = migrated["token"] as String
        core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId, "dry_run" to false, "token" to migrateToken, "migrate" to true))
        assertTrue(ownedEvents.any { it.key.endsWith(":due:d") })
        assertTrue(ownedEvents.none { it.key.endsWith(":class:old") })
    }

    @Test fun failedWritesSkipDeletion() {
        ownedEvents += owned("zju:2026-2027-1:class:old", "旧课", "2026-09-01T08:00", "2026-09-01T09:35")
        val planId = writePlan(listOf(event("zju:2026-2027-1:class:new", "新课", "2026-09-02T08:00")))
        val dry = json(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId)))
        failWrites = true
        val token = dry["token"] as String
        val out = core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId, "dry_run" to false, "token" to token))
        assertTrue(out.isError)
        assertTrue(ownedEvents.any { it.key.endsWith(":class:old") })
    }

    @Test fun allDayPlanEventsUseUtcMidnights() {
        val planId = writePlan(
            listOf(event("zju:2026-2027-1:due:d", "全天截止", "2026-10-03", "2026-10-04", allDay = true, reminders = listOf(1440))),
        )
        val dry = json(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId)))
        val token = dry["token"] as String
        val out = core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId, "dry_run" to false, "token" to token))
        assertFalse(out.isError)
        val saved = ownedEvents.single()
        assertTrue(saved.allDay)
        assertEquals("UTC", saved.timezone)
        assertEquals(LocalDate.of(2026, 10, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), saved.beginMs)
        assertEquals(LocalDate.of(2026, 10, 4).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), saved.endMs)
    }

    @Test fun listOwnedFiltersByScopeAndStaysOnAppEvents() {
        ownedEvents += owned("zju:2026-2027-1:class:a", "课", "2026-09-02T08:00", "2026-09-02T09:00")
        ownedEvents += owned("zju:2026-2027-2:class:b", "另一学期", "2027-02-20T08:00", "2027-02-20T09:00")
        val out = json(core.call(CalendarToolCatalog.LIST_OWNED, mapOf("scope" to "zju:2026-2027-1")))
        assertEquals(1, number(out, "count"))
        val events = out["events"] as List<*>
        assertEquals(1, events.size)
        assertEquals("课", (events[0] as Map<*, *>)["title"])
    }

    @Test fun invalidPlanIdsAndOversizedPlansAreRejected() {
        assertTrue(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to "../outside")).isError)
        assertTrue(core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to "missingplan")).isError)
        val events = (1..CalendarCore.MAX_EVENTS + 1).map {
            event("zju:2026-2027-1:class:$it", "课 $it", "2026-09-02T08:00")
        }
        val planId = writePlan(events)
        val out = core.call(CalendarToolCatalog.SYNC, mapOf("plan_id" to planId))
        assertTrue(out.isError)
        assertTrue(out.text.contains("超过上限"))
    }

    private fun json(out: ToolOutput): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return Json.parse(out.text) as Map<String, Any?>
    }

    private fun number(map: Any?, key: String): Int? = ((map as? Map<*, *>)?.get(key) as? Number)?.toInt()

    private fun event(
        key: String,
        title: String,
        begin: String,
        end: String? = null,
        allDay: Boolean = false,
        location: String? = null,
        description: String? = null,
        reminders: List<Int> = listOf(10),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "key" to key,
        "kind" to key.substringAfter(':').substringBefore(':'),
        "title" to title,
        "begin" to begin,
    ).apply {
        if (end != null) put("end", end)
        put("all_day", allDay)
        if (location != null) put("location", location)
        if (description != null) put("description", description)
        put("reminder_minutes", reminders)
    }

    private fun writePlan(
        events: List<Map<String, Any?>>,
        scope: String = "zju:2026-2027-1",
        preserveKinds: List<String> = emptyList(),
        warnings: List<String> = emptyList(),
    ): String {
        planNumber++
        val id = "plan$planNumber"
        val plan = linkedMapOf<String, Any?>(
            "version" to 1,
            "scope" to scope,
            "label" to "2026-2027 秋冬",
            "range" to mapOf("from" to "2026-09-14", "to" to "2027-01-03"),
            "events" to events,
            "preserve_kinds" to preserveKinds,
            "warnings" to warnings,
        )
        File(planRoot, "$id.json").writeText(Json.write(plan))
        return id
    }

    private fun owned(
        key: String,
        title: String,
        begin: String,
        end: String,
        calendarId: Long = 1L,
        reminders: List<Int> = emptyList(),
        description: String? = null,
    ): OwnedCalendarEvent {
        val beginMs = LocalDateTime.parse(begin).atZone(zone).toInstant().toEpochMilli()
        val endMs = LocalDateTime.parse(end).atZone(zone).toInstant().toEpochMilli()
        return OwnedCalendarEvent(
            eventId = nextId++,
            calendarId = calendarId,
            key = key,
            title = title,
            beginMs = beginMs,
            endMs = endMs,
            allDay = false,
            timezone = zone.id,
            location = null,
            description = description,
            reminderMinutes = reminders,
        )
    }
}
