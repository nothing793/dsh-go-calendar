package io.github.ccch1mneyyy.dsh_app.calendar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.util.concurrent.Executors

/** Flutter bridge for the built-in calendar MCP: MethodChannel `dsh/calendar`. */
class CalendarPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler, PluginRegistry.RequestPermissionsResultListener {
    private lateinit var methods: MethodChannel
    private lateinit var appContext: Context
    private lateinit var control: CalendarControl
    private var activity: ActivityPluginBinding? = null
    private var pendingPermission: MethodChannel.Result? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-calendar-plugin").apply { isDaemon = true } }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        control = CalendarControl.get(appContext)
        methods = MethodChannel(binding.binaryMessenger, "dsh/calendar").also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pendingPermission?.success(false)
        pendingPermission = null
        methods.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)

    override fun onDetachedFromActivity() {
        activity?.removeRequestPermissionsResultListener(this)
        activity = null
        pendingPermission?.success(false)
        pendingPermission = null
    }

    private fun async(result: MethodChannel.Result, block: () -> Any?) {
        io.execute {
            val value = try {
                Result.success(block())
            } catch (e: Throwable) {
                Result.failure(e)
            }
            main.post {
                value.fold(
                    onSuccess = { result.success(it) },
                    onFailure = { result.error("calendar_failed", it.message ?: it.javaClass.simpleName, null) },
                )
            }
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "status" -> async(result) { control.status() }
            "setEnabled" -> async(result) { control.setEnabled(call.argument<Boolean>("enabled") == true) }
            "calendars" -> async(result) { control.calendars().filter { it.writable }.map { it.toMap() } }
            "setCalendar" -> async(result) {
                val id = call.argument<String>("id")?.toLongOrNull()
                if (id == null) mapOf("ok" to false) else mapOf("ok" to control.setSelected(id))
            }
            "requestPermission" -> requestPermission(result)
            "openAppSettings" -> result.success(openAppSettings())
            else -> result.notImplemented()
        }
    }

    private fun requestPermission(result: MethodChannel.Result) {
        if (control.hasPermission()) {
            result.success(true)
            return
        }
        if (pendingPermission != null) {
            result.error("calendar_permission_pending", "A calendar permission request is already showing.", null)
            return
        }
        val binding = activity
        if (binding == null) {
            result.success(false)
            return
        }
        pendingPermission = result
        binding.activity.requestPermissions(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            PERMISSION_REQUEST,
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        val read = permissions.indexOf(Manifest.permission.READ_CALENDAR)
        val write = permissions.indexOf(Manifest.permission.WRITE_CALENDAR)
        val granted = read >= 0 && write >= 0 &&
            grantResults.getOrNull(read) == PackageManager.PERMISSION_GRANTED &&
            grantResults.getOrNull(write) == PackageManager.PERMISSION_GRANTED
        pendingPermission?.success(granted)
        pendingPermission = null
        return true
    }

    private fun openAppSettings(): Boolean = try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}"))
        val host: Activity? = activity?.activity
        if (host != null) host.startActivity(intent) else appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val PERMISSION_REQUEST = 6201
    }
}
