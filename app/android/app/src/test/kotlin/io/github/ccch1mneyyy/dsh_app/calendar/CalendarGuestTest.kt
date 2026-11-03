package io.github.ccch1mneyyy.dsh_app.calendar

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CalendarGuestTest {
    @Test fun launchArgAddsTheCalendarOverlayOnce() {
        val arg = "/usr/local/bin/dsh web --host 127.0.0.1 --patch /etc/dsh-app/dsh-web.patch.yml"
        val withCalendar = CalendarGuest.launchArg(arg)
        assertNotNull(withCalendar)
        assertTrue(withCalendar!!.contains("--patch ${CalendarGuest.PATCH}"))
        assertEquals(1, Regex("--patch ${Regex.escape(CalendarGuest.PATCH)}").findAll(withCalendar).count())
        assertNull(CalendarGuest.launchArg("/usr/local/bin/dsh web --profile other"))
    }

    @Test fun guestFilesAndConfigAreWrittenUnderTheirOwnDirectory() {
        val root = kotlin.io.path.createTempDirectory("calendar-guest").toFile()
        try {
            CalendarGuest.writeFiles(root)
            val relay = File(root, CalendarGuest.RELAY.removePrefix("/"))
            val patch = File(root, CalendarGuest.PATCH.removePrefix("/"))
            assertTrue(relay.isFile)
            assertTrue(patch.isFile)
            assertTrue(patch.readText().contains("serverName: calendar"))
            assertTrue(CalendarGuest.AGENTS_SECTION.contains("mcp__calendar__upsert"))
            assertTrue(CalendarGuest.AGENTS_SECTION.contains("mcp__calendar__sync"))
            assertTrue(CalendarGuest.AGENTS_SECTION.contains("mcp__calendar__list_owned"))
            CalendarGuest.writeConfig(root, io.github.ccch1mneyyy.dsh_app.phone.PhoneGuest.Endpoint("test", null, "token"))
            val config = File(root, CalendarGuest.CONFIG.removePrefix("/"))
            assertTrue(config.isFile)
            assertTrue(config.readText().contains("\"token\":\"token\""))
            CalendarGuest.clearConfig(root)
            assertFalse(config.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
