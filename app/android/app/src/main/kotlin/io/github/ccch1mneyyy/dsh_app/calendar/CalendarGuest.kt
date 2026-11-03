package io.github.ccch1mneyyy.dsh_app.calendar

import io.github.ccch1mneyyy.dsh_app.phone.PhoneGuest
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The engine side of the calendar MCP. The App writes a relay and a `--patch` overlay into the
 * rootfs at every start; DSH mounts @deepseek-ai/dsh-mcp-client as server `calendar`, so the model
 * sees `mcp__calendar__*`. The relay carries no tool definitions: they live in the APK.
 */
object CalendarGuest {
    const val GUEST_DIR = "/opt/dsh-app/calendar"
    const val RELAY = "$GUEST_DIR/relay.cjs"
    const val PATCH = "$GUEST_DIR/calendar.patch.yml"
    const val STATE_DIR = "/var/lib/dsh-app/calendar"
    const val CONFIG = "$STATE_DIR/bridge.json"
    const val PLAN_DIR = "/root/.dsh/zju-campus/calendar-plans"
    const val SERVER_NAME = "calendar"
    const val TOOL_CALL_TIMEOUT_MS = 30_000L

    private const val WEB_PATCH_ARG = "--patch /etc/dsh-app/dsh-web.patch.yml"
    private const val DSH_WEB = "/usr/local/bin/dsh web"

    fun launchArg(arg: String): String? {
        val web = arg.indexOf("$DSH_WEB ")
        if (web < 0 || arg.indexOf("$DSH_WEB ", web + 1) >= 0 || arg.contains("--profile")) return null
        val patch = arg.indexOf(WEB_PATCH_ARG, web)
        val at = if (patch >= 0 && arg.indexOf(WEB_PATCH_ARG, patch + 1) < 0) patch + WEB_PATCH_ARG.length else web + DSH_WEB.length
        return arg.substring(0, at) + " --patch $PATCH" + arg.substring(at)
    }

    // l10n:keep agent config comment
    val PATCH_YAML = """# dsh-app: built-in calendar MCP. The DSH App rewrites this file at every engine start and
# passes it with --patch only while 设置 → 日历 is on (never in safe mode). Edits do not survive.
- insert:
    - id: dsh-app-calendar
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: $SERVER_NAME
        transport: stdio
        command: !!js process.execPath
        args:
          - $RELAY
          - $CONFIG
        toolCallTimeoutMs: $TOOL_CALL_TIMEOUT_MS
        failOnStartupError: false
"""

    // l10n:keep agent prompt
    val AGENTS_SECTION = """
        |
        |## 手机日历
        |
        |- 用 `mcp__calendar__list` 查可写日历。单条事件用 `mcp__calendar__upsert` / `mcp__calendar__remove`；整学期计划用 `mcp__calendar__sync`，先用 `dry_run: true` 把新增、更新、删除条数和警告给用户看，用户明确确认后再用 `dry_run: false` 和返回的 `token` 写入。不要逐条 upsert 几百条事件。
        |- `mcp__calendar__list_owned` 只列出本 App 写入的事件，用来核对同步结果。不要读或改用户自己建的事件。
        |- 同一件事始终用同一个 `key`；更新不会重复建事件。只操作带 DSH 标识的事件，不读用户日历里的其他内容。
        |- 只在用户明确要写手机日历时用；日程时间按手机时区写，全天事件写 `2026-11-05` 这样的日期。
        |""".trimMargin()

    fun writeFiles(rootfs: File) {
        val dir = realDir(guestFile(rootfs, GUEST_DIR), ownerOnly = false)
        writeAtomic(File(dir, "relay.cjs"), PhoneGuest.RELAY_JS, ownerOnly = false)
        writeAtomic(File(dir, "calendar.patch.yml"), PATCH_YAML, ownerOnly = false)
    }

    fun writeConfig(rootfs: File, endpoint: PhoneGuest.Endpoint) {
        val dir = realDir(guestFile(rootfs, STATE_DIR), ownerOnly = true)
        writeAtomic(File(dir, "bridge.json"), PhoneGuest.config(endpoint), ownerOnly = true)
    }

    fun clearConfig(rootfs: File) {
        try {
            guestFile(rootfs, CONFIG).delete()
        } catch (_: Exception) {
        }
    }

    /** The plan directory the campus plugin writes to ($DSH_HOME/zju-campus/calendar-plans). */
    fun planDir(rootfs: File): File = guestFile(rootfs, PLAN_DIR)

    fun guestFile(rootfs: File, guestPath: String): File = File(rootfs, guestPath.removePrefix("/"))

    private fun realDir(dir: File, ownerOnly: Boolean): File {
        if (Files.isSymbolicLink(dir.toPath()) || (dir.exists() && !dir.isDirectory)) dir.delete()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
        if (ownerOnly) restrict(dir, exec = true)
        return dir
    }

    private fun restrict(f: File, exec: Boolean) {
        f.setReadable(false, false)
        f.setWritable(false, false)
        f.setExecutable(false, false)
        f.setReadable(true, true)
        f.setWritable(true, true)
        if (exec) f.setExecutable(true, true)
    }

    private fun writeAtomic(file: File, text: String, ownerOnly: Boolean) {
        val tmp = File(file.parentFile, ".${file.name}.dsh-app-${System.nanoTime()}.tmp")
        try {
            tmp.createNewFile()
            if (ownerOnly) restrict(tmp, exec = false) else tmp.setReadable(true, false)
            tmp.writeText(text)
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.path}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
