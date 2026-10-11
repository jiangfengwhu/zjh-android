package com.zijinhua.app.shell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Callback waits suspend coroutines; provider and file IO have separate bounded pools. */
object ShellOperations {
  val methods = setOf("operationCancel", "operationStopApp", "operationSuspendApp", "location", "pick", "exportFile", "share", "fileCommit", "fileReadTextChunk",
    "device", "torch", "openUrl", "calendarEvents", "calendarInsert", "calendarRemove",
    "healthStatus", "healthPermissions", "requestHealth", "healthSummary",
    "bluetoothState", "bluetoothDevices", "bluetoothScan", "nfcStatus", "nfcRead")
  private val main = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val providerExecutor = Executors.newFixedThreadPool(2)
  internal val providerIo = providerExecutor.asCoroutineDispatcher()
  private val cancelling = Executors.newSingleThreadExecutor()
  private data class Task(val appId: String, val method: String, val host: Activity?, val job: Job)
  private val tasks = mutableMapOf<String, Task>() // main looper
  private val torches = mutableMapOf<String, String>()
  private var application: Context? = null
  internal fun result(error: String? = null) = JSONObject().put("ok", error == null).apply { if (error != null) put("error", error) }
  private fun cancelApp(appId: String) { tasks.values.filter { it.appId == appId }.toList().forEach { it.job.cancel() }; stopTorch(appId) }
  fun stopApp(appId: String) { main.post { cancelApp(appId) } }
  fun stopAll() { main.post { tasks.values.toList().forEach { it.job.cancel() }; torches.keys.toList().forEach { stopTorch(it) } } }
  fun stopActivity(activity: Activity) { main.post { tasks.values.filter { it.host === activity }.forEach { it.job.cancel() }; torches.keys.toList().forEach { stopTorch(it) } } }
  fun pauseInteractive() { main.post {
    tasks.values.filter { it.method in setOf("location", "bluetoothScan", "nfcRead") }.forEach { it.job.cancel() }
    torches.keys.toList().forEach { stopTorch(it) }
  } }
  fun call(context: Context, method: String, input: JSONObject, reply: (JSONObject) -> Unit, isCurrent: () -> Boolean = { true }) {
    main.post {
      if (!isCurrent()) return@post
      try {
        application = context.applicationContext
        val appId = input.getString("appId").also { require(it.matches(Regex("[a-z][a-z0-9-]{0,63}"))) }
        if (method == "operationSuspendApp") {
          tasks.values.filter { it.appId == appId && it.method in setOf("location", "bluetoothScan", "nfcRead") }.forEach { it.job.cancel() }
          stopTorch(appId); reply(result()); return@post
        }
        if (method == "operationStopApp") { cancelApp(appId); reply(result()); return@post }
        val requestId = input.getString("requestId").also { require(it.matches(Regex("[A-Za-z0-9._:-]{1,160}"))) }
        val key = "$appId:$requestId"
        if (method == "operationCancel") { tasks[key]?.job?.cancel(); reply(result()); return@post }
        if (tasks.size >= 24 || tasks.values.count { it.appId == appId } >= 6 || tasks.containsKey(key)) { reply(result("busy")); return@post }
        val host = ShellCapabilities.hostActivity()
        val job = scope.launch(start = CoroutineStart.LAZY) {
          val value = try {
            val timeout = input.optLong("timeoutMs", if (method in setOf("pick", "exportFile", "requestHealth")) 120000 else 30000)
            require(timeout in 1000..180000)
            withTimeout(timeout) {
              when (method) {
                "location" -> location(context, input)
                "fileReadTextChunk" -> provider { _, alive -> ShellTextFiles.read(context, input, alive) }
                "fileCommit" -> provider { _, alive ->
                  val root = java.io.File(context.filesDir, "applications/$appId").canonicalFile
                  val target = java.io.File(input.getString("path")).canonicalFile
                  val source = java.io.File(input.getString("temporary")).canonicalFile
                  require(target.path.startsWith(root.path + java.io.File.separator))
                  require(source.parentFile == target.parentFile && source != target && source.isFile)
                  check(alive())
                  android.system.Os.rename(source.path, target.path)
                  result()
                }
                "pick" -> ShellFileTransfers.importFile(context, input)
                "exportFile" -> ShellFileTransfers.export(context, input)
                "share" -> ShellFileTransfers.share(context, input)
                "healthStatus" -> ShellHealth.status(context)
                "healthPermissions" -> ShellHealth.permissions(context)
                "requestHealth" -> ShellHealth.request(context, input)
                "healthSummary" -> ShellHealth.summary(context, input)
                "bluetoothState" -> ShellBluetooth.state(context)
                "bluetoothDevices" -> ShellBluetooth.devices(context)
                "bluetoothScan" -> ShellBluetooth.scan(context, input)
                "nfcStatus" -> ShellNfc.status(context)
                "nfcRead" -> ShellNfc.read(context, input)
                "device" -> device(context)
                "torch" -> torch(context, input, appId)
                "openUrl" -> { requireNotNull(host).startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(input.getString("url")))); result() }
                "calendarEvents" -> provider { signal, alive -> calendarEvents(context, input, signal, alive) }
                "calendarInsert" -> provider { signal, alive -> calendarInsert(context, input, signal, alive) }
                "calendarRemove" -> provider { _, alive -> check(alive()); calendarRemove(context, input) }
                else -> result("unsupported")
              }
            }
          } catch (_: TimeoutCancellationException) { result("timeout") }
            catch (_: CancellationException) { result("cancel") }
            catch (_: SecurityException) { result("permission") }
            catch (_: Exception) { result("unavailable") }
          tasks.remove(key)
          reply(value)
        }
        tasks[key] = Task(appId, method, host, job); job.start()
      } catch (_: Exception) { reply(result("unavailable")) }
    }
  }
  private suspend fun <T> provider(block: (CancellationSignal, () -> Boolean) -> T): T = suspendCancellableCoroutine { continuation ->
    val signal = CancellationSignal()
    val future = providerExecutor.submit {
      try {
        if (continuation.isActive) {
          val value = block(signal) { continuation.isActive }
          if (continuation.isActive) continuation.resume(value)
        }
      } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
    }
    continuation.invokeOnCancellation {
      future.cancel(true); (providerExecutor as java.util.concurrent.ThreadPoolExecutor).remove(future as Runnable)
      cancelling.execute { signal.cancel() }
    }
  }
  private suspend fun location(context: Context, input: JSONObject): JSONObject {
    val fine = input.optString("accuracy", "coarse") == "fine"
    val permission = if (fine) android.Manifest.permission.ACCESS_FINE_LOCATION else android.Manifest.permission.ACCESS_COARSE_LOCATION
    if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) return result("permission")
    val manager = context.getSystemService(LocationManager::class.java) ?: return result("unsupported")
    val providers = (if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER))
      .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    if (providers.isEmpty()) return result("disabled")
    // Race available providers. First fresh fix wins; neither holds a bridge thread.
    return suspendCancellableCoroutine { continuation ->
      val signals = mutableListOf<CancellationSignal>()
      val listeners = mutableListOf<android.location.LocationListener>()
      fun clean() { signals.forEach { it.cancel() }; listeners.forEach { runCatching { manager.removeUpdates(it) } } }
      fun deliver(fix: android.location.Location) {
        if (continuation.isActive) { clean(); continuation.resume(result().put("latitude", fix.latitude).put("longitude", fix.longitude).put("accuracy", fix.accuracy).put("timestampMs", fix.time)) }
      }
      continuation.invokeOnCancellation { main.post { clean() } }
      try {
        if (Build.VERSION.SDK_INT >= 30) {
          var outstanding = providers.size
          providers.forEach { provider ->
            if (continuation.isActive) {
              val signal = CancellationSignal(); signals.add(signal)
              manager.getCurrentLocation(provider, signal, context.mainExecutor) { fix ->
                if (fix != null) deliver(fix)
                else if (--outstanding == 0 && continuation.isActive) { clean(); continuation.resume(result("unavailable")) }
              }
            }
          }
        } else providers.forEach { provider ->
          val listener = android.location.LocationListener { deliver(it) }; listeners.add(listener)
          @Suppress("DEPRECATION")
          manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
      } catch (error: Exception) { clean(); if (continuation.isActive) continuation.resume(result(if (error is SecurityException) "permission" else "unavailable")) }
    }
  }
  private fun device(context: Context): JSONObject {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return result().put("brand", Build.BRAND).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
      .put("battery", if (level >= 0 && scale > 0) level * 100 / scale else -1).put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
  }
  private fun torch(context: Context, input: JSONObject, appId: String): JSONObject {
    val on = input.optBoolean("on")
    if (!on) { stopTorch(appId); return result() }
    if (torches.keys.any { it != appId }) return result("busy")
    val manager = context.getSystemService(CameraManager::class.java) ?: return result("unsupported")
    val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return result("unsupported")
    manager.setTorchMode(id, true); torches[appId] = id; return result()
  }
  private fun stopTorch(appId: String) { torches.remove(appId)?.let { id -> runCatching { application?.getSystemService(CameraManager::class.java)?.setTorchMode(id, false) } } }
  private fun requirePermission(context: Context, permission: String) { if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) throw SecurityException() }
  private fun calendarEvents(context: Context, input: JSONObject, signal: CancellationSignal, alive: () -> Boolean): JSONObject {
    requirePermission(context, android.Manifest.permission.READ_CALENDAR)
    val from = input.getLong("fromMs"); val to = input.getLong("toMs"); require(from >= 0 && to > from && to - from <= 366L * 86400000)
    val offset = input.optInt("offset", 0).also { require(it >= 0) }; val limit = input.optInt("limit", 50).also { require(it in 1..200) }
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
    val items = JSONArray(); var more = false
    context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY), null, null, "${CalendarContract.Instances.BEGIN} ASC, ${CalendarContract.Instances.EVENT_ID} ASC", signal)?.use { cursor ->
      if (offset > 0) cursor.moveToPosition(offset - 1)
      while (cursor.moveToNext()) {
        check(alive())
        if (items.length() == limit) { more = true; break }
        items.put(JSONObject().put("id", cursor.getLong(0).toString()).put("title", cursor.getString(1) ?: "").put("startMs", cursor.getLong(2)).put("endMs", cursor.getLong(3)).put("allDay", cursor.getInt(4) != 0))
      }
    }
    return result().put("items", items).put("nextOffset", if (more) offset + items.length() else JSONObject.NULL)
  }
  private fun calendarInsert(context: Context, input: JSONObject, signal: CancellationSignal, alive: () -> Boolean): JSONObject {
    requirePermission(context, android.Manifest.permission.WRITE_CALENDAR)
    requirePermission(context, android.Manifest.permission.READ_CALENDAR)
    val start = input.getLong("startMs"); val end = input.getLong("endMs"); require(start > 0 && end > start)
    val title = input.getString("title").also { require(it.isNotBlank() && it.length <= 200) }
    val calendarId = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?", arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null, signal)?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return result("unavailable")
    val allDay = input.optBoolean("allDay")
    require(!allDay || start % 86400000 == 0L && end % 86400000 == 0L)
    val values = ContentValues().apply {
      put(CalendarContract.Events.CALENDAR_ID, calendarId); put(CalendarContract.Events.TITLE, title)
      put(CalendarContract.Events.DESCRIPTION, input.optString("description").take(2000)); put(CalendarContract.Events.EVENT_LOCATION, input.optString("location").take(300))
      put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, end); put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
      put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else java.util.TimeZone.getDefault().id)
    }
    check(alive())
    val saved = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return result("unavailable")
    return result().put("id", saved.lastPathSegment)
  }
  private fun calendarRemove(context: Context, input: JSONObject): JSONObject {
    requirePermission(context, android.Manifest.permission.WRITE_CALENDAR)
    val id = input.getString("id").toLong().also { require(it > 0) }
    return result().put("removed", context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0)
  }
}
