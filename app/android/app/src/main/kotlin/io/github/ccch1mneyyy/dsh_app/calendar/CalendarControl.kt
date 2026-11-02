package io.github.ccch1mneyyy.dsh_app.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import io.github.ccch1mneyyy.dsh_app.phone.McpServer
import io.github.ccch1mneyyy.dsh_app.phone.PhoneBridge
import java.io.File
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Process-wide owner of the built-in calendar MCP: the separate switch, permission check,
 * selected writable calendar, and the bridge lifetime around the engine.
 *
 * Calendar is deliberately not part of 「手机操控」: it needs only READ/WRITE_CALENDAR, not the
 * accessibility and screen-control surface.
 */
class CalendarControl private constructor(private val app: Context) : CalendarEnv {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val store = AndroidCalendarStore(app)
    private val ticker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dsh-calendar").apply { isDaemon = true }
    }

    private val core = CalendarCore(this)
    private val server = McpServer(CalendarToolCatalog.listJson(), core::call, SERVER_VERSION)
    private val bridge = PhoneBridge(server) { line -> logger(line) }

    @Volatile private var logger: (String) -> Unit = {}
    @Volatile private var rootfs: File? = null

    fun isEnabled(): Boolean = prefs.getBoolean(K_ENABLED, false)

    /** Turning off closes the bridge immediately; turning on needs an engine restart to mount it. */
    fun setEnabled(enabled: Boolean): Map<String, Any?> {
        if (enabled) {
            if (!hasPermission()) return mapOf("ok" to false, "reason" to "先允许 DSH 访问手机日历。")
            val writable = calendars().filter { it.writable }
            if (writable.isEmpty()) return mapOf("ok" to false, "reason" to "这台手机上还没有可写的日历。")
            if (selected() == null) return mapOf("ok" to false, "reason" to "先选择一个可写日历。")
        }
        prefs.edit().putBoolean(K_ENABLED, enabled).apply()
        if (!enabled) stopBridge()
        return mapOf("ok" to true)
    }

    override fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun enabled(): Boolean = isEnabled()

    override fun calendars(): List<CalendarInfo> = try {
        if (!hasPermission()) emptyList() else store.calendars()
    } catch (_: Exception) {
        emptyList()
    }

    override fun selected(): CalendarInfo? {
        if (!hasPermission()) return null
        val writable = calendars().filter { it.writable }
        if (writable.isEmpty()) return null
        val id = prefs.getLong(K_CALENDAR_ID, -1L)
        writable.firstOrNull { it.id == id }?.let { return it }
        val pick = writable.firstOrNull { it.primary } ?: writable.first()
        prefs.edit().putLong(K_CALENDAR_ID, pick.id).putString(K_CALENDAR_NAME, pick.name).apply()
        return pick
    }

    fun setSelected(id: Long): Boolean {
        val pick = calendars().firstOrNull { it.id == id && it.writable } ?: return false
        prefs.edit().putLong(K_CALENDAR_ID, pick.id).putString(K_CALENDAR_NAME, pick.name).apply()
        return true
    }

    override fun upsert(event: CalendarEvent, calendarId: Long): CalendarWriteResult {
        return try {
            store.upsert(event, calendarId)
        } catch (e: SecurityException) {
            CalendarWriteResult(false, "DSH 没有手机日历权限。请用户到 设置 → 日历 里允许访问。")
        } catch (e: Exception) {
            CalendarWriteResult(false, "写入手机日历失败：${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}")
        }
    }

    override fun remove(key: String, calendarId: Long?): CalendarWriteResult = try {
        store.remove(key, calendarId)
    } catch (e: SecurityException) {
        CalendarWriteResult(false, "DSH 没有手机日历权限。请用户到 设置 → 日历 里允许访问。")
    } catch (e: Exception) {
        CalendarWriteResult(false, "删除手机日历事件失败：${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}")
    }

    override fun zone(): ZoneId = ZoneId.systemDefault()

    fun status(): Map<String, Any?> {
        val granted = hasPermission()
        val list = try {
            if (granted) calendars().filter { it.writable } else emptyList()
        } catch (e: Exception) {
            emptyList<CalendarInfo>()
        }
        val selected = if (granted) runCatching { selected() }.getOrNull() else null
        val permission = when {
            granted -> "granted"
            app.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED ||
                app.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED -> "partial"
            else -> "missing"
        }
        return linkedMapOf(
            "enabled" to isEnabled(),
            "permission" to permission,
            "selected" to selected?.toMap(),
            "calendars" to list.map { it.toMap() },
            "bridgeListening" to bridge.listening,
            "ready" to (isEnabled() && granted && selected != null),
        )
    }

    /**
     * Before DSH launches: with the switch on and not in safe mode, writes the guest files, starts
     * the bridge and returns true (the launch then adds the `--patch` overlay). Otherwise makes
     * sure nothing is listening and returns false.
     */
    fun prepareLaunch(rootfs: File, safe: Boolean, log: (String) -> Unit): Boolean {
        logger = log
        stopBridge()
        this.rootfs = rootfs
        if (!isEnabled() || safe) {
            CalendarGuest.clearConfig(rootfs)
            if (isEnabled()) log("[dsh-app] calendar: stays off in safe mode")
            return false
        }
        if (!hasPermission()) {
            log("[dsh-app] calendar: no READ/WRITE_CALENDAR permission; tools are off")
            CalendarGuest.clearConfig(rootfs)
            return false
        }
        if (selected() == null) {
            log("[dsh-app] calendar: no writable calendar selected; tools are off")
            CalendarGuest.clearConfig(rootfs)
            return false
        }
        return try {
            CalendarGuest.writeFiles(rootfs)
            val ep = bridge.start(withTcp = prefs.getBoolean(K_TCP_FALLBACK, false))
            CalendarGuest.writeConfig(rootfs, ep)
            true
        } catch (e: Exception) {
            log("[dsh-app] calendar: setup failed (${e.message}); starting without calendar tools")
            stopBridge()
            false
        }
    }

    /** After the launch command was built: false means the runtime template had no `dsh web` anchor. */
    fun launched(mounted: Boolean) {
        if (mounted) return
        logger("[dsh-app] calendar: this runtime's launch command has no `dsh web` to add the calendar overlay to; calendar tools are off")
        stopBridge()
    }

    fun onEngineReady() {
        ticker.schedule({ checkReached() }, 5, TimeUnit.SECONDS)
    }

    private fun checkReached() {
        if (!bridge.listening || bridge.authenticatedConnections > 0) return
        val ep = bridge.endpoint ?: return
        if (ep.tcpPort != null) {
            logger("[dsh-app] calendar: DSH did not connect to the calendar bridge")
            return
        }
        logger("[dsh-app] calendar: DSH did not reach the unix socket; adding the loopback fallback")
        try {
            val next = bridge.addTcp() ?: return
            rootfs?.let { CalendarGuest.writeConfig(it, next) }
            prefs.edit().putBoolean(K_TCP_FALLBACK, true).apply()
        } catch (e: Exception) {
            logger("[dsh-app] calendar: loopback fallback failed: ${e.message}")
        }
    }

    fun onEngineStopped() {
        stopBridge()
    }

    private fun stopBridge() {
        bridge.stop()
        rootfs?.let { CalendarGuest.clearConfig(it) }
    }

    companion object {
        const val SERVER_VERSION = "1"
        private const val PREFS = "dsh_calendar"
        private const val K_ENABLED = "enabled"
        private const val K_CALENDAR_ID = "calendarId"
        private const val K_CALENDAR_NAME = "calendarName"
        private const val K_TCP_FALLBACK = "tcpFallback"

        @Volatile private var instance: CalendarControl? = null

        fun get(context: Context): CalendarControl =
            instance ?: synchronized(this) {
                instance ?: CalendarControl(context.applicationContext).also { instance = it }
            }
    }
}
