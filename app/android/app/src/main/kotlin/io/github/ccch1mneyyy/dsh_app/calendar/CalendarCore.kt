package io.github.ccch1mneyyy.dsh_app.calendar

import io.github.ccch1mneyyy.dsh_app.phone.Args
import io.github.ccch1mneyyy.dsh_app.phone.IntentSpec
import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.phone.ToolOutput
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/** Argument checks, plan validation, diffing and token handling for the calendar MCP. No Android APIs here. */
class CalendarCore(
    private val env: CalendarEnv,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val keyPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val scopePattern = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._:-]{0,126}[A-Za-z0-9])?$")
    private val planIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,63}$")
    private val kindPattern = Regex("^[A-Za-z][A-Za-z0-9_-]{0,31}$")
    private val tokenRandom = SecureRandom()
    private val pending = HashMap<String, Pending>()
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

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
                CalendarToolCatalog.SYNC -> sync(Args(args))
                CalendarToolCatalog.LIST_OWNED -> listOwned(Args(args))
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
        val reminders = parseIntList(a.raw("reminder_minutes"))
            ?: return ToolOutput("reminder_minutes 要是整数数组。", isError = true)
        val built = buildEvent(
            key = key,
            title = title,
            beginText = beginText,
            endText = a.str("end"),
            allDayArg = a.bool("all_day"),
            location = a.str("location")?.take(200),
            description = a.str("description")?.take(4000),
            reminders = reminders,
        )
        built.second?.let { return ToolOutput(it, isError = true) }
        val calendar = chooseCalendar(a.str("calendar_id"))
        calendar.second?.let { return ToolOutput(it, isError = true) }
        val result = env.upsert(built.first!!, calendar.first!!.id)
        return ToolOutput(result.message, isError = !result.ok)
    }

    private fun remove(a: Args): ToolOutput {
        // l10n:keep agent tool validation
        val key = a.str("key") ?: return ToolOutput("remove 需要 key。", isError = true)
        if (!keyPattern.matches(key)) {
            // l10n:keep agent tool validation
            return ToolOutput("key 只能含字母、数字、点、下划线、冒号和短横线，长度 1–128，且要以字母或数字开头。", isError = true)
        }
        val rawCalendar = a.str("calendar_id")
        val calendarId = if (rawCalendar == null) {
            null
        } else {
            val calendar = chooseCalendar(rawCalendar)
            calendar.second?.let { return ToolOutput(it, isError = true) }
            calendar.first!!.id
        }
        val result = env.remove(key, calendarId)
        return ToolOutput(result.message, isError = !result.ok)
    }

    private fun sync(a: Args): ToolOutput {
        // l10n:keep agent tool validation
        val planId = a.str("plan_id") ?: return ToolOutput("sync 需要 plan_id（由校园插件生成）。", isError = true)
        if (!planIdPattern.matches(planId)) {
            // l10n:keep agent tool validation
            return ToolOutput("plan_id 只能含字母、数字和短横线，长度 1–64。", isError = true)
        }
        val calendar = chooseCalendar(a.str("calendar_id"))
        calendar.second?.let { return ToolOutput(it, isError = true) }
        val target = calendar.first!!
        val migrate = a.bool("migrate") ?: false
        val dryRun = a.bool("dry_run") ?: true

        val loaded = loadPlan(planId)
        loaded.second?.let { return ToolOutput(it, isError = true) }
        val plan = loaded.first!!
        val existing = env.owned(plan.scope, null)
        val diff = diff(plan, existing, target, migrate)
        val binding = binding(plan.text, target.id, migrate, existing)
        val warnings = plan.warnings + diff.warnings(target)

        if (dryRun) {
            val token = newToken(binding)
            val out = linkedMapOf<String, Any?>(
                "ok" to true,
                "dry_run" to true,
                "plan_id" to planId,
                "scope" to plan.scope,
                "label" to plan.label,
                "range" to plan.range,
                "calendar" to target.toMap(),
                "counts" to plan.counts,
                "changes" to diff.changes(),
                "warnings" to warnings,
                "token" to token,
                "expires_in_seconds" to TOKEN_TTL_SECONDS,
            )
            return ToolOutput(Json.write(out))
        }

        // l10n:keep agent tool validation
        val token = a.str("token") ?: return ToolOutput("正式写入要带 dry-run 返回的 token；先带 dry_run=true 预览。", isError = true)
        val tokenError = checkToken(token, binding)
        if (tokenError != null) return ToolOutput(tokenError, isError = true)

        val applied = apply(diff, target)
        val out = linkedMapOf<String, Any?>(
            "ok" to applied.errors.isEmpty(),
            "dry_run" to false,
            "plan_id" to planId,
            "scope" to plan.scope,
            "label" to plan.label,
            "calendar" to target.toMap(),
            "changes" to applied.counts,
            "warnings" to warnings,
            "errors" to applied.errors,
        )
        return ToolOutput(Json.write(out), isError = applied.errors.isNotEmpty())
    }

    private fun listOwned(a: Args): ToolOutput {
        val scope = a.str("scope")
        if (scope != null && !scopePattern.matches(scope)) {
            // l10n:keep agent tool validation
            return ToolOutput("scope 只能含字母、数字、点、下划线、冒号和短横线，长度 1–128。", isError = true)
        }
        val rawCalendar = a.str("calendar_id")
        val calendarId = if (rawCalendar == null) {
            null
        } else {
            val calendar = chooseCalendar(rawCalendar)
            calendar.second?.let { return ToolOutput(it, isError = true) }
            calendar.first!!.id
        }
        val events = env.owned(scope, calendarId)
        val shown = events.take(MAX_LIST_OWNED)
        val out = linkedMapOf<String, Any?>(
            "count" to events.size,
            "truncated" to (events.size > shown.size),
            "events" to shown.map { ownedMap(it) },
        )
        return ToolOutput(Json.write(out))
    }

    private fun chooseCalendar(raw: String?): Pair<CalendarInfo?, String?> {
        if (raw == null) {
            val selected = env.selected()
                // l10n:keep agent tool validation
                ?: return null to "还没有选中的可写日历。请用户在 设置 → 日历 里选一个。"
            return selected to null
        }
        val id = raw.toLongOrNull()
            // l10n:keep agent tool validation
            ?: return null to "calendar_id 要是数字 id。先用 list 看可写的 id。"
        return env.calendars().firstOrNull { it.id == id && it.writable }?.let { it to null }
            // l10n:keep agent tool validation
            ?: (null to "calendar_id=$id 不是可写日历。先用 list 看可写的 id。")
    }

    private fun buildEvent(
        key: String,
        title: String,
        beginText: String,
        endText: String?,
        allDayArg: Boolean?,
        location: String?,
        description: String?,
        reminders: List<Int>,
    ): Pair<CalendarEvent?, String?> {
        val parsed = IntentSpec.parseTime(beginText, env.zone())
            // l10n:keep agent tool validation
            ?: return null to "begin 看不懂：定时事件写 2026-11-05T14:00，全天事件写 2026-11-05。"
        val allDay = allDayArg ?: parsed.dateOnly
        val begin = (if (allDay) allDayStart(beginText, parsed.ms) else TimedStart(parsed.ms, env.zone().id))
            // l10n:keep agent tool validation
            ?: return null to "全天事件的 begin 要写成 2026-11-05。"
        val end = if (allDay) {
            if (endText == null) {
                begin.date!!.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } else {
                val endDate = parseDate(endText)
                    // l10n:keep agent tool validation
                    ?: return null to "全天事件的 end 要写成 2026-11-06；它是结束日期，不含当天。"
                endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
        } else {
            if (endText == null) begin.ms + 3_600_000L
            else {
                val at = IntentSpec.parseTime(endText, env.zone())
                    // l10n:keep agent tool validation
                    ?: return null to "end 看不懂：定时事件写 2026-11-05T15:00。"
                at.ms
            }
        }
        // l10n:keep agent tool validation
        if (end <= begin.ms) return null to "end 要晚于 begin。"
        if (reminders.size > 8) {
            // l10n:keep agent tool validation
            return null to "reminder_minutes 最多写 8 个。"
        }
        if (reminders.any { it !in 0..40_320 }) {
            // l10n:keep agent tool validation
            return null to "reminder_minutes 的每一项要在 0–40320 分钟之间（最多四周）。"
        }
        return CalendarEvent(
            key = key,
            title = title.ifBlank { "未命名日程" },
            beginMs = begin.ms,
            endMs = end,
            allDay = allDay,
            timezone = begin.zone,
            location = location,
            description = description,
            reminderMinutes = reminders.distinct().sorted(),
        ) to null
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

    // ── batch sync ─────────────────────────────────────────

    private data class PlannedEvent(val event: CalendarEvent, val kind: String)

    private data class PlanData(
        val scope: String,
        val label: String,
        val range: Map<String, String>,
        val events: List<PlannedEvent>,
        val counts: Map<String, Int>,
        val preserveKinds: Set<String>,
        val warnings: List<String>,
        val text: String,
    )

    private data class Diff(
        val creates: List<PlannedEvent>,
        val updates: List<Pair<PlannedEvent, OwnedCalendarEvent>>,
        val unchanged: Int,
        val removals: List<OwnedCalendarEvent>,
        val kept: List<OwnedCalendarEvent>,
        val preserved: List<OwnedCalendarEvent>,
    ) {
        fun changes(): Map<String, Int> = linkedMapOf(
            "create" to creates.size,
            "update" to updates.size,
            "remove" to removals.size,
            "unchanged" to unchanged,
        )

        fun warnings(target: CalendarInfo): List<String> {
            val out = mutableListOf<String>()
            val oldRemovals = removals.count { it.calendarId != target.id }
            if (kept.isNotEmpty()) {
                // l10n:keep agent tool result
                out += "旧日历里还有 ${kept.size} 条本学期的 DSH 事件；确认迁移后可用 migrate=true 清理。"
            }
            if (preserved.isNotEmpty()) {
                // l10n:keep agent tool result
                out += "按计划保留 ${preserved.size} 条旧事件（对应来源本次没有刷新）。"
            }
            if (oldRemovals > 0) {
                // l10n:keep agent tool result
                out += "写入成功后会从其他日历清理 $oldRemovals 条本学期事件。"
            }
            return out
        }
    }

    private data class Applied(val counts: Map<String, Int>, val errors: List<String>)

    private data class Pending(val binding: String, val expiresAt: Long)

    private fun loadPlan(planId: String): Pair<PlanData?, String?> {
        val dir = try {
            env.planDir()?.canonicalFile
        } catch (_: Exception) {
            null
        }
            // l10n:keep agent tool result
            ?: return null to "日历 MCP 还没拿到运行环境，读不到计划文件。请重启引擎后再试。"
        if (!dir.isDirectory) {
            // l10n:keep agent tool result
            return null to "还没有生成过日历计划。请先让校园插件生成本学期的计划。"
        }
        val file = try {
            File(dir, "$planId.json").canonicalFile
        } catch (_: Exception) {
            // l10n:keep agent tool validation
            return null to "plan_id 不对。"
        }
        if (file.parentFile != dir) {
            // l10n:keep agent tool validation
            return null to "plan_id 不对。"
        }
        if (!file.isFile) {
            // l10n:keep agent tool result
            return null to "没有找到计划 $planId。请让校园插件重新生成计划。"
        }
        if (file.length() > MAX_PLAN_BYTES) {
            // l10n:keep agent tool validation
            return null to "计划文件太大，拒绝读取。"
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            // l10n:keep agent tool result
            return null to "计划文件读不了：${e.message ?: e.javaClass.simpleName}"
        }
        val raw = try {
            Json.parse(text)
        } catch (e: Exception) {
            // l10n:keep agent tool result
            return null to "计划文件不是合法 JSON：${e.message ?: e.javaClass.simpleName}"
        }
        val map = raw as? Map<*, *>
            // l10n:keep agent tool result
            ?: return null to "计划文件格式不对：顶层要是对象。"
        val version = (map["version"] as? Number)?.toInt()
        if (version != 1) {
            // l10n:keep agent tool result
            return null to "计划文件版本不支持：${map["version"]}。"
        }
        val scope = (map["scope"] as? String)?.trim().orEmpty()
        if (!scopePattern.matches(scope)) {
            // l10n:keep agent tool result
            return null to "计划里的 scope 不合法。"
        }
        val label = ((map["label"] as? String)?.trim().orEmpty()).ifEmpty { scope }.take(200)
        val range = (map["range"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            val value = v as? String ?: return@mapNotNull null
            key to value
        }.toMap()
        val preserveKinds = when (val value = map["preserve_kinds"]) {
            null -> emptySet()
            is List<*> -> value.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.toSet()
            // l10n:keep agent tool result
            else -> return null to "计划里的 preserve_kinds 要是字符串数组。"
        }
        val warnings = when (val value = map["warnings"]) {
            null -> emptyList()
            is List<*> -> value.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.take(50)
            // l10n:keep agent tool result
            else -> return null to "计划里的 warnings 要是字符串数组。"
        }
        val rawEvents = map["events"] as? List<*>
            // l10n:keep agent tool result
            ?: return null to "计划文件格式不对：缺少 events 数组。"
        if (rawEvents.size > MAX_EVENTS) {
            // l10n:keep agent tool validation
            return null to "计划里有 ${rawEvents.size} 条事件，超过上限 $MAX_EVENTS。"
        }
        val seen = HashSet<String>()
        val events = ArrayList<PlannedEvent>(rawEvents.size)
        for ((index, item) in rawEvents.withIndex()) {
            val eventMap = item as? Map<*, *>
                // l10n:keep agent tool result
                ?: return null to "计划第 ${index + 1} 条不是对象。"
            val key = (eventMap["key"] as? String)?.trim().orEmpty()
            if (!keyPattern.matches(key)) {
                // l10n:keep agent tool result
                return null to "计划第 ${index + 1} 条的 key 不合法。"
            }
            if (!key.startsWith("$scope:")) {
                // l10n:keep agent tool result
                return null to "计划第 ${index + 1} 条的 key 不在 scope $scope 里。"
            }
            if (!seen.add(key)) {
                // l10n:keep agent tool result
                return null to "计划里有重复的 key：$key。"
            }
            val title = (eventMap["title"] as? String)?.trim().orEmpty()
            if (title.isEmpty()) {
                // l10n:keep agent tool result
                return null to "计划第 ${index + 1} 条缺少 title。"
            }
            val begin = (eventMap["begin"] as? String)?.trim().orEmpty()
            if (begin.isEmpty()) {
                // l10n:keep agent tool result
                return null to "计划第 ${index + 1} 条缺少 begin。"
            }
            val reminders = parseIntList(eventMap["reminder_minutes"])
                // l10n:keep agent tool result
                ?: return null to "计划第 ${index + 1} 条的 reminder_minutes 要是整数数组。"
            val kind = (eventMap["kind"] as? String)?.trim()?.takeIf { kindPattern.matches(it) }
                ?: kindOf(key, scope)
                ?: "event"
            val built = buildEvent(
                key = key,
                title = title.take(200),
                beginText = begin,
                endText = eventMap["end"] as? String,
                allDayArg = eventMap["all_day"] as? Boolean,
                location = (eventMap["location"] as? String)?.take(200),
                description = (eventMap["description"] as? String)?.take(4000),
                reminders = reminders,
            )
            built.second?.let {
                // l10n:keep agent tool result
                return null to "计划第 ${index + 1} 条：$it"
            }
            events += PlannedEvent(built.first!!, kind)
        }
        val counts = LinkedHashMap<String, Int>()
        for (event in events) counts[event.kind] = (counts[event.kind] ?: 0) + 1
        return PlanData(scope, label, range, events, counts, preserveKinds, warnings, text) to null
    }

    private fun diff(plan: PlanData, existing: List<OwnedCalendarEvent>, target: CalendarInfo, migrate: Boolean): Diff {
        val byKey = existing.groupBy { it.key }
        val desiredKeys = plan.events.map { it.event.key }.toSet()
        val used = HashSet<Long>()
        val creates = ArrayList<PlannedEvent>()
        val updates = ArrayList<Pair<PlannedEvent, OwnedCalendarEvent>>()
        var unchanged = 0

        for (planned in plan.events) {
            val candidates = byKey[planned.event.key].orEmpty()
            val chosen = candidates.firstOrNull { it.calendarId == target.id } ?: candidates.firstOrNull()
            if (chosen == null) {
                creates += planned
            } else {
                used += chosen.eventId
                if (chosen.calendarId == target.id && chosen.toEvent() == planned.event) unchanged++
                else updates += planned to chosen
            }
        }

        val removals = ArrayList<OwnedCalendarEvent>()
        val kept = ArrayList<OwnedCalendarEvent>()
        val preserved = ArrayList<OwnedCalendarEvent>()
        for (event in existing) {
            if (event.eventId in used) continue
            val stale = event.key !in desiredKeys
            val kind = kindOf(event.key, plan.scope)
            if (stale && kind != null && kind in plan.preserveKinds) {
                preserved += event
            } else if (event.calendarId == target.id) {
                removals += event
            } else if (migrate) {
                removals += event
            } else {
                kept += event
            }
        }
        return Diff(creates, updates, unchanged, removals, kept, preserved)
    }

    private fun apply(diff: Diff, target: CalendarInfo): Applied {
        val errors = ArrayList<String>()
        var create = 0
        var update = 0
        for (planned in diff.creates) {
            val result = env.upsert(planned.event, target.id, null)
            if (result.ok) create++ else errors += "新增失败「${planned.event.title}」：${result.message}"
        }
        for ((planned, existing) in diff.updates) {
            val result = env.upsert(planned.event, target.id, existing.eventId)
            if (result.ok) update++ else errors += "更新失败「${planned.event.title}」：${result.message}"
        }
        var remove = 0
        if (errors.isEmpty()) {
            for (event in diff.removals) {
                val result = env.removeEvent(event.eventId)
                if (result.ok) remove++ else errors += "删除失败「${event.title}」：${result.message}"
            }
        } else if (diff.removals.isNotEmpty()) {
            // l10n:keep agent tool result
            errors += "有写入失败，已跳过删除，避免丢失旧事件。"
        }
        return Applied(
            linkedMapOf("create" to create, "update" to update, "remove" to remove, "unchanged" to diff.unchanged),
            errors,
        )
    }

    private fun kindOf(key: String, scope: String): String? {
        if (!key.startsWith("$scope:")) return null
        return key.removePrefix("$scope:").substringBefore(':').takeIf { kindPattern.matches(it) }
    }

    private fun ownedMap(event: OwnedCalendarEvent): Map<String, Any?> = linkedMapOf(
        "key" to event.key,
        "title" to event.title,
        "calendar_id" to event.calendarId.toString(),
        "begin" to formatTime(event.beginMs, event.allDay),
        "end" to formatTime(event.endMs, event.allDay),
        "all_day" to event.allDay,
    )

    private fun formatTime(ms: Long, allDay: Boolean): String {
        val instant = Instant.ofEpochMilli(ms)
        return if (allDay) instant.atZone(ZoneOffset.UTC).toLocalDate().toString()
        else instant.atZone(env.zone()).toLocalDateTime().format(timeFormat)
    }

    private fun parseIntList(value: Any?): List<Int>? {
        if (value == null) return emptyList()
        val items = value as? List<*> ?: listOf(value)
        val out = ArrayList<Int>(items.size)
        for (item in items) {
            val number = when (item) {
                is Number -> {
                    val d = item.toDouble()
                    if (d != Math.rint(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
                    d.toInt()
                }
                is String -> item.trim().toIntOrNull() ?: return null
                else -> return null
            }
            out += number
        }
        return out
    }

    private fun newToken(binding: String): String {
        pruneTokens()
        val bytes = ByteArray(24)
        tokenRandom.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        synchronized(pending) { pending[token] = Pending(binding, now() + TOKEN_TTL_MS) }
        return token
    }

    private fun checkToken(token: String, binding: String): String? {
        val entry = synchronized(pending) { pending.remove(token) }
            // l10n:keep agent tool validation
            ?: return "确认令牌无效或已经用过；请重新 dry-run。"
        if (entry.expiresAt < now()) {
            // l10n:keep agent tool validation
            return "预览已过期，请重新 dry-run。"
        }
        if (entry.binding != binding) {
            // l10n:keep agent tool validation
            return "预览后计划或手机日历变了，请重新 dry-run 让用户确认。"
        }
        return null
    }

    private fun pruneTokens() {
        val at = now()
        synchronized(pending) { pending.entries.removeAll { it.value.expiresAt < at } }
    }

    private fun binding(planText: String, calendarId: Long, migrate: Boolean, existing: List<OwnedCalendarEvent>): String {
        val material = buildString {
            append(planText).append('\n').append(calendarId).append('\n').append(migrate).append('\n')
            for (event in existing.sortedWith(compareBy({ it.key }, { it.eventId }))) {
                append(event.eventId).append('|').append(event.calendarId).append('|').append(event.key).append('|')
                append(event.title).append('|').append(event.beginMs).append('|').append(event.endMs).append('|')
                append(event.allDay).append('|').append(event.timezone).append('|')
                append(event.location.orEmpty()).append('|').append(event.description.orEmpty()).append('|')
                append(event.reminderMinutes.joinToString(",")).append('\n')
            }
        }
        return sha256(material)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }

    companion object {
        const val MAX_EVENTS = 1000
        const val MAX_LIST_OWNED = 500
        const val MAX_PLAN_BYTES = 4L * 1024 * 1024
        const val TOKEN_TTL_SECONDS = 600
        const val TOKEN_TTL_MS = TOKEN_TTL_SECONDS * 1000L
    }
}
