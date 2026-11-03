package io.github.ccch1mneyyy.dsh_app.calendar

import io.github.ccch1mneyyy.dsh_app.phone.Json
import org.junit.Assert.*
import org.junit.Test

class CalendarToolSchemaTest {
    @Test fun calendarToolsKeepAStableSmallSchema() {
        assertEquals(5, CalendarToolCatalog.tools.size)
        assertEquals(listOf("list", "upsert", "remove", "sync", "list_owned"), CalendarToolCatalog.names)
        val json = Json.write(CalendarToolCatalog.listJson())
        assertFalse(json.contains("\"minimum\""))
        assertFalse(json.contains("\"maximum\""))
        val upsert = CalendarToolCatalog.tools.single { it.name == CalendarToolCatalog.UPSERT }
        assertEquals(listOf("key", "title", "begin"), upsert.required)
        val reminders = upsert.properties["reminder_minutes"] as Map<*, *>
        assertEquals("array", reminders["type"])
        assertEquals(mapOf("type" to "integer"), reminders["items"])
        val remove = CalendarToolCatalog.tools.single { it.name == CalendarToolCatalog.REMOVE }
        assertEquals(listOf("key"), remove.required)
        val sync = CalendarToolCatalog.tools.single { it.name == CalendarToolCatalog.SYNC }
        assertEquals(listOf("plan_id"), sync.required)
        assertNotNull(sync.properties["token"])
        assertNotNull(sync.properties["dry_run"])
        assertNotNull(sync.properties["migrate"])
        val owned = CalendarToolCatalog.tools.single { it.name == CalendarToolCatalog.LIST_OWNED }
        assertTrue(owned.required.isEmpty())
        assertNotNull(owned.properties["scope"])
    }
}
