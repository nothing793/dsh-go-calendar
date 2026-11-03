package io.github.ccch1mneyyy.dsh_app.calendar

/**
 * The fixed tools served as `mcp__calendar__*`. Names and descriptions enter every model
 * request, so keep them stable.
 */
object CalendarToolCatalog {
    const val LIST = "list"
    const val UPSERT = "upsert"
    const val REMOVE = "remove"
    const val SYNC = "sync"
    const val LIST_OWNED = "list_owned"

    class Tool(
        val name: String,
        val description: String,
        val properties: Map<String, Any?>,
        val required: List<String>,
    ) {
        fun toJson(): Map<String, Any?> = linkedMapOf(
            "name" to name,
            "description" to description,
            "inputSchema" to linkedMapOf<String, Any?>("type" to "object", "properties" to properties).apply {
                if (required.isNotEmpty()) put("required", required)
                put("additionalProperties", false)
            },
        )
    }

    private fun str(description: String) = linkedMapOf<String, Any?>("type" to "string", "description" to description)
    private fun bool(description: String) = linkedMapOf<String, Any?>("type" to "boolean", "description" to description)

    val tools: List<Tool> = listOf(
        Tool(
            LIST,
            // l10n:keep agent tool catalog
            "列出这台手机上可写的日历。只有用户在 DSH Go 设置 → 日历 里允许访问后才能用。",
            emptyMap(),
            emptyList(),
        ),
        Tool(
            UPSERT,
            // l10n:keep agent tool catalog
            "新增或更新一条手机日历事件。key 是稳定标识；同一个 key 再写会更新原事件，不会重复创建。" +
                "begin/end 用手机时区，如 2026-11-05T14:00；全天事件写 2026-11-05，end 是结束日期（不含当天），省略时只占 begin 当天。" +
                "只操作带 DSH 标识的事件。整学期同步用 sync，不要逐条 upsert。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "key" to str("稳定标识，如 dsh-zju:exam:2026-11-12:高等数学；同一件事始终用同一个 key"),
                // l10n:keep agent tool catalog
                "title" to str("日程标题"),
                // l10n:keep agent tool catalog
                "begin" to str("开始，如 2026-11-05T14:00；全天写 2026-11-05"),
                // l10n:keep agent tool catalog
                "end" to str("结束，省略 = 定时事件开始后 1 小时、全天事件只占开始当天"),
                // l10n:keep agent tool catalog
                "all_day" to bool("是否全天；begin 只有日期时默认全天"),
                // l10n:keep agent tool catalog
                "calendar_id" to str("目标日历 id；省略时用用户在设置里选中的日历"),
                // l10n:keep agent tool catalog
                "location" to str("地点"),
                // l10n:keep agent tool catalog
                "description" to str("备注"),
                // l10n:keep agent tool catalog
                "reminder_minutes" to linkedMapOf(
                    "type" to "array",
                    "items" to linkedMapOf("type" to "integer"),
                    // l10n:keep agent tool catalog
                    "description" to "提前多少分钟提醒，可写多个，如 [1440, 120]；最多 8 个",
                ),
            ),
            listOf("key", "title", "begin"),
        ),
        Tool(
            REMOVE,
            // l10n:keep agent tool catalog
            "按 key 删除 DSH 写入的手机日历事件。只删除带 DSH 标识的事件；用户自己建的事件不会动。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "key" to str("upsert 时用的稳定标识"),
                // l10n:keep agent tool catalog
                "calendar_id" to str("目标日历 id；省略时用设置里选中的日历"),
            ),
            listOf("key"),
        ),
        Tool(
            SYNC,
            // l10n:keep agent tool catalog
            "按校园插件生成的计划批量同步手机日历。先 dry_run=true 返回新增/更新/删除条数和 token；用户确认后，再 dry_run=false 并带 token 写入。" +
                "计划文件里的 key 必须属于同一个 scope；只操作 DSH 写入的事件。默认 dry_run=true。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "plan_id" to str("校园插件生成的计划编号，如 phone_calendar 返回的 plan_id"),
                // l10n:keep agent tool catalog
                "token" to str("dry-run 返回的确认令牌；正式写入时必须原样带回"),
                // l10n:keep agent tool catalog
                "dry_run" to bool("true 只预览；false 正式写入。默认 true"),
                // l10n:keep agent tool catalog
                "calendar_id" to str("目标日历 id；省略时用设置里选中的日历"),
                // l10n:keep agent tool catalog
                "migrate" to bool("换日历后是否清理旧日历里本学期的 DSH 事件；默认 false"),
            ),
            listOf("plan_id"),
        ),
        Tool(
            LIST_OWNED,
            // l10n:keep agent tool catalog
            "列出 DSH 写入的手机日历事件，用于核对同步结果。只返回本 App 写入的事件，不读用户自己建的事件。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "scope" to str("只看某个学期，如 zju:2026-2027-1；省略时列出全部 DSH 事件"),
                // l10n:keep agent tool catalog
                "calendar_id" to str("只看某个日历 id"),
            ),
            emptyList(),
        ),
    )

    val names: List<String> = tools.map { it.name }

    fun listJson(): List<Map<String, Any?>> = tools.map { it.toJson() }
}
