package io.github.ccch1mneyyy.dsh_app.calendar

import io.github.ccch1mneyyy.dsh_app.phone.Args
import io.github.ccch1mneyyy.dsh_app.phone.IntentSpec
import io.github.ccch1mneyyy.dsh_app.phone.ToolOutput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Argument checks and time normalization for the calendar MCP. No Android APIs here. */
class CalendarCore(private val env: CalendarEnv) {
    private val keyPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    fun call(name: String, args: Map<String, Any?>): ToolOutput {
        if (name !in CalendarToolCatalog.names) {
            // l10n:keep agent tool result
            return ToolOutput("没有这个日历工具：$name。可用：${CalendarToolCatalog.names.joinToString("、")}", isError = true)
        }
        if (!env.enabled()) {
            // l10n:keep agent tool result
            return ToolOutput("手机日历同步已在 DSH 设置 → 日历 里关闭。请用户打开后再试。", isError = true)
        }
        if (!env.hasPermission()) {
            // l10n:keep agent tool result
            return ToolOutput("DSH 还没有手机日历权限。请用户到 设置 → 日历 里允许访问。", isError = true)
        }
        return try {
            when (name) {
                CalendarToolCatalog.LIST -> list()
                CalendarToolCatalog.UPSERT -> upsert(Args(args))
                CalendarToolCatalog.REMOVE -> remove(Args(args))
                else -> error("unreachable")
            }
        } catch (e: Exception) {
            // l10n:keep agent tool result
            ToolOutput("日历操作出错：${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}", isError = true)
        }
    }

    private fun list(): ToolOutput {
        val calendars = env.calendars().filter { it.writable }
        if (calendars.isEmpty()) {
            // l10n:keep agent tool result
            return ToolOutput("没有可写的手机日历。请用户先在系统日历 App 里建一个日历，或检查 DSH 的日历权限。", isError = true)
        }
        val selected = env.selected()
        val text = buildString {
            // l10n:keep agent tool result
            append("可写的手机日历：\n")
            calendars.forEach {
                append("- ").append(it.name)
                if (it.account.isNotBlank() && it.account != it.name) append("（").append(it.account).append("）")
                append(" · id=").append(it.id)
                if (it.primary) append(" · 默认")
                append('\n')
            }
            selected?.let {
                // l10n:keep agent tool result
                append("\n当前选中：").append(it.name).append("（id=").append(it.id).append("）")
            }
        }.trimEnd()
        return ToolOutput(text)
    }

    private fun upsert(a: Args): ToolOutput {
        // l10n:keep agent tool validation
        val key = a.str("key") ?: return ToolOutput("upsert 需要 key（同一件事始终用同一个稳定标识）。", isError = true)
        if (!keyPattern.matches(key)) {
            // l10n:keep agent tool validation
            return ToolOutput("key 只能含字母、数字、点、下划线、冒号和短横线，长度 1–128，且要以字母或数字开头。", isError = true)
        }
        // l10n:keep agent tool validation
        val title = a.str("title")?.take(200) ?: return ToolOutput("upsert 需要 title。", isError = true)
        // l10n:keep agent tool validation
        val beginText = a.str("begin") ?: return ToolOutput("upsert 需要 begin，例如 2026-11-05T14:00。", isError = true)
        val parsed = IntentSpec.parseTime(beginText, env.zone())
        // l10n:keep agent tool validation
            ?: return ToolOutput("begin 看不懂：定时事件写 2026-11-05T14:00，全天事件写 2026-11-05。", isError = true)
        val allDay = a.bool("all_day") ?: parsed.dateOnly
        val begin = (if (allDay) allDayStart(beginText, parsed.ms) else TimedStart(parsed.ms, env.zone().id))
            // l10n:keep agent tool validation
            ?: return ToolOutput("全天事件的 begin 要写成 2026-11-05。", isError = true)
        val endText = a.str("end")
        val end = if (allDay) {
            if (endText == null) {
                begin.date!!.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } else {
                val endDate = parseDate(endText)
                // l10n:keep agent tool validation
                    ?: return ToolOutput("全天事件的 end 要写成 2026-11-06；它是结束日期，不含当天。", isError = true)
                endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
        } else {
            if (endText == null) begin.ms + 3_600_000L
            else {
                val at = IntentSpec.parseTime(endText, env.zone())
                // l10n:keep agent tool validation
                    ?: return ToolOutput("end 看不懂：定时事件写 2026-11-05T15:00。", isError = true)
                at.ms
            }
        }
        // l10n:keep agent tool validation
        if (end <= begin.ms) return ToolOutput("end 要晚于 begin。", isError = true)

        val reminders = a.intList("reminder_minutes")?.distinct()?.sorted() ?: emptyList()
        if (reminders.size > 8) {
            // l10n:keep agent tool validation
            return ToolOutput("reminder_minutes 最多写 8 个。", isError = true)
        }
        if (reminders.any { it !in 0..40_320 }) {
            // l10n:keep agent tool validation
            return ToolOutput("reminder_minutes 的每一项要在 0–40320 分钟之间（最多四周）。", isError = true)
        }

        val requestedCalendar = a.str("calendar_id")?.toLongOrNull()
        val calendar = if (requestedCalendar != null) {
            env.calendars().firstOrNull { it.id == requestedCalendar && it.writable }
                // l10n:keep agent tool validation
                ?: return ToolOutput("calendar_id=$requestedCalendar 不是可写日历。先用 list 看可写的 id。", isError = true)
        } else {
            env.selected()
                // l10n:keep agent tool validation
                ?: return ToolOutput("还没有选中的可写日历。请用户在 设置 → 日历 里选一个。", isError = true)
        }

        val result = env.upsert(
            CalendarEvent(
                key = key,
                title = title.ifBlank { "未命名日程" },
                beginMs = begin.ms,
                endMs = end,
                allDay = allDay,
                timezone = begin.zone,
                location = a.str("location")?.take(200),
                description = a.str("description")?.take(4000),
                reminderMinutes = reminders,
            ),
            calendar.id,
        )
        return ToolOutput(result.message, isError = !result.ok)
    }

    private fun remove(a: Args): ToolOutput {
        // l10n:keep agent tool validation
        val key = a.str("key") ?: return ToolOutput("remove 需要 key。", isError = true)
        if (!keyPattern.matches(key)) {
            // l10n:keep agent tool validation
            return ToolOutput("key 只能含字母、数字、点、下划线、冒号和短横线，长度 1–128，且要以字母或数字开头。", isError = true)
        }
        val requestedCalendar = a.str("calendar_id")?.toLongOrNull()
        requestedCalendar?.let { id ->
            // l10n:keep agent tool validation
            if (env.calendars().none { it.id == id && it.writable }) return ToolOutput("calendar_id=$id 不是可写日历。", isError = true)
        }
        val result = env.remove(key, requestedCalendar)
        return ToolOutput(result.message, isError = !result.ok)
    }

    private data class TimedStart(val ms: Long, val zone: String, val date: LocalDate? = null)

    private fun allDayStart(text: String, parsedMs: Long): TimedStart? {
        val date = parseDate(text) ?: Instant.ofEpochMilli(parsedMs).atZone(env.zone()).toLocalDate()
        return TimedStart(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), "UTC", date)
    }

    private fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text.trim().take(10))
    } catch (_: Exception) {
        null
    }
}
