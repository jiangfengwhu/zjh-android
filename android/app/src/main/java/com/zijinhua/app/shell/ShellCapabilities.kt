package com.zijinhua.app.shell

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.zijinhua.app.plugin.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ShellCapabilities {
  private const val TAG = "ShellCapabilities"
  private var activity: ComponentActivity? = null
  private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
  private var pickLauncher: ActivityResultLauncher<String>? = null
  private var captureLauncher: ActivityResultLauncher<Uri>? = null
  private var exportLauncher: ActivityResultLauncher<Intent>? = null
  private val exportWait = AtomicReference<Waiter<Uri?>?>(null)
  private val permissionWait = AtomicReference<Waiter<Map<String, Boolean>>?>(null)
  private val pickWait = AtomicReference<Waiter<Uri?>?>(null)
  private val captureWait = AtomicReference<Waiter<Boolean>?>(null)
  private var captureFile: File? = null

  private val aliases = mapOf(
    "notifications" to Manifest.permission.POST_NOTIFICATIONS,
    "camera" to Manifest.permission.CAMERA,
    "microphone" to Manifest.permission.RECORD_AUDIO,
    "location" to Manifest.permission.ACCESS_FINE_LOCATION,
    "locationCoarse" to Manifest.permission.ACCESS_COARSE_LOCATION,
    "locationBackground" to Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    "photos" to Manifest.permission.READ_MEDIA_IMAGES,
    "videos" to Manifest.permission.READ_MEDIA_VIDEO,
    "audio" to Manifest.permission.READ_MEDIA_AUDIO,
    "contacts" to Manifest.permission.READ_CONTACTS,
    "contactsWrite" to Manifest.permission.WRITE_CONTACTS,
    "calendar" to Manifest.permission.READ_CALENDAR,
    "calendarWrite" to Manifest.permission.WRITE_CALENDAR,
    "activity" to Manifest.permission.ACTIVITY_RECOGNITION,
    "bluetooth" to Manifest.permission.BLUETOOTH_CONNECT,
    "bluetoothScan" to Manifest.permission.BLUETOOTH_SCAN,
    "bluetoothAdvertise" to Manifest.permission.BLUETOOTH_ADVERTISE,
    "phone" to Manifest.permission.CALL_PHONE,
    "sms" to Manifest.permission.READ_SMS,
    "smsSend" to Manifest.permission.SEND_SMS,
    "callLog" to Manifest.permission.READ_CALL_LOG,
  )

  fun attach(host: ComponentActivity) {
    activity = host
    permissionLauncher = host.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
      permissionWait.getAndSet(null)?.finish(granted)
    }
    pickLauncher = host.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      pickWait.getAndSet(null)?.finish(uri)
    }
    captureLauncher = host.registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
      captureWait.getAndSet(null)?.finish(ok)
    }
    exportLauncher = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      exportWait.getAndSet(null)?.finish(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
    }
    ShellHealth.attach(host)
    Log.i(TAG, "activity attached")
  }

  fun detach(host: ComponentActivity) {
    if (activity === host) {
      activity = null
      pickWait.getAndSet(null)?.finish(null)
      exportWait.getAndSet(null)?.finish(null)
    }
  }

  internal fun hostActivity(): ComponentActivity? = activity

  internal fun hostContext() = appContext()

  fun register(registry: PluginRegistry) {
    registry.register("shell", "capabilities") { _ -> capabilities().toString() }
    registry.register("shell", "permissions") { _ -> permissions().toString() }
    registry.register("shell", "requestPermission") { payload -> requestPermission(JSONObject(payload)).toString() }
    registry.register("shell", "notify") { payload -> notify(JSONObject(payload)).toString() }
    registry.register("shell", "schedule") { payload -> schedule(JSONObject(payload)).toString() }
    registry.register("shell", "cancelSchedule") { payload -> cancelSchedule(JSONObject(payload)).toString() }
    registry.register("shell", "schedules") { _ -> schedules().toString() }
    registry.register("shell", "startForeground") { payload -> startForeground(JSONObject(payload)).toString() }
    registry.register("shell", "stopForeground") { _ -> stopForeground().toString() }
    registry.register("shell", "share") { payload -> share(JSONObject(payload)).toString() }
    registry.register("shell", "exportFile") { payload -> exportFile(JSONObject(payload)).toString() }
    registry.register("shell", "openSettings") { payload -> openSettings(JSONObject(payload)).toString() }
    registry.register("shell", "authenticate") { payload -> authenticate(JSONObject(payload)).toString() }
    registry.register("shell", "location") { payload -> location(JSONObject(payload)).toString() }
    registry.register("shell", "pick") { payload -> pick(JSONObject(payload)).toString() }
    registry.register("shell", "capturePhoto") { _ -> capturePhoto().toString() }
    registry.register("shell", "installPackage") { payload -> installPackage(JSONObject(payload)).toString() }
  }

  private fun appContext() = activity?.applicationContext
    ?: throw IllegalStateException("shell activity missing")

  private fun capabilities(): JSONObject {
    val names = JSONArray()
    aliases.keys.sorted().forEach { names.put(it) }
    val methods = JSONArray()
    listOf(
      "capabilities", "permissions", "requestPermission", "notify", "schedule", "cancelSchedule",
      "schedules", "startForeground", "stopForeground", "share", "exportFile", "openSettings", "authenticate",
      "location", "pick", "capturePhoto", "installPackage",
      "device", "clipboardGet", "clipboardSet", "contacts", "calendarEvents", "calendarInsert", "openUrl",
      "vibrate", "motion", "torch", "keepAwake", "speak", "fileRead", "fileWrite", "fileList",
      "healthStatus", "healthPermissions", "requestHealth", "healthSummary",
      "bluetoothState", "bluetoothDevices", "bluetoothScan", "nfcStatus", "nfcRead",
      "smsList", "smsSend", "callLog",
      "appearance", "systemBars", "bundleReady", "loadApp", "providers", "saveProvider", "deleteProvider", "complete", "completeCancel",
    ).forEach { methods.put(it) }
    return JSONObject().put("ok", true).put("permissions", names).put("methods", methods)
  }

  private fun permissions(): JSONObject {
    val context = appContext()
    val items = JSONObject()
    aliases.forEach { (name, permission) ->
      items.put(name, granted(context, permission))
    }
    return JSONObject().put("ok", true).put("items", items)
  }

  private fun requestPermission(input: JSONObject): JSONObject {
    val name = input.getString("name")
    val permission = aliases[name] ?: throw IllegalArgumentException("unknown permission $name")
    val host = activity ?: throw IllegalStateException("shell activity missing")
    if (granted(host, permission)) {
      return JSONObject().put("ok", true).put("name", name).put("granted", true)
    }
    val launcher = permissionLauncher ?: throw IllegalStateException("permission launcher missing")
    val waiter = Waiter<Map<String, Boolean>>()
    if (!permissionWait.compareAndSet(null, waiter)) throw IllegalStateException("permission request busy")
    host.runOnUiThread { launcher.launch(arrayOf(permission)) }
    val result = waiter.await()
    val ok = result[permission] == true
    Log.i(TAG, "permission $name granted=$ok")
    return JSONObject().put("ok", true).put("name", name).put("granted", ok)
  }

  private fun notify(input: JSONObject): JSONObject {
    val context = appContext()
    val id = ShellAlarms.requireId(input.getString("id"))
    ShellAlarms.show(context, id, input.optString("title"), input.optString("body"))
    return JSONObject().put("ok", true).put("id", id)
  }

  private fun schedule(input: JSONObject): JSONObject {
    return ShellAlarms.schedule(
      appContext(),
      input.getString("id"),
      input.optString("title"),
      input.optString("body"),
      input.getLong("atMs"),
    )
  }

  private fun cancelSchedule(input: JSONObject): JSONObject {
    return ShellAlarms.cancel(appContext(), input.getString("id"))
  }

  private fun schedules(): JSONObject = ShellAlarms.list(appContext())

  private fun startForeground(input: JSONObject): JSONObject {
    val context = appContext()
    val type = input.optString("type", "dataSync")
    ShellForegroundService.start(context, input.optString("title", "紫金花"), input.optString("body"), type)
    return JSONObject().put("ok", true).put("type", type)
  }

  private fun stopForeground(): JSONObject {
    ShellForegroundService.stop(appContext())
    return JSONObject().put("ok", true)
  }

  private fun share(input: JSONObject): JSONObject {
    val host = activity ?: throw IllegalStateException("shell activity missing")
    val send = Intent(Intent.ACTION_SEND)
    val path = input.optString("path")
    if (path.isNotBlank()) {
      val file = ownedFile(host, path)
      val uri = FileProvider.getUriForFile(host, "${host.packageName}.fileprovider", file)
      send.type = input.optString("mime", "application/octet-stream")
      send.putExtra(Intent.EXTRA_STREAM, uri)
      send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
      send.type = "text/plain"
      send.putExtra(Intent.EXTRA_TEXT, input.optString("text"))
    }
    send.putExtra(Intent.EXTRA_SUBJECT, input.optString("title"))
    val waiter = Waiter<Boolean>()
    host.runOnUiThread { try { host.startActivity(Intent.createChooser(send, input.optString("title", "分享"))); waiter.finish(true) } catch (_: Exception) { waiter.finish(false) } }
    return JSONObject().put("ok", waiter.await())
  }

  private fun exportFile(input: JSONObject): JSONObject {
    val host = activity ?: throw IllegalStateException("shell activity missing")
    val launcher = exportLauncher ?: throw IllegalStateException("export launcher missing")
    val file = ownedFile(host, input.getString("path"))
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
      .setType(input.optString("mime", "application/octet-stream"))
      .putExtra(Intent.EXTRA_TITLE, input.optString("name").ifBlank { file.name })
    val waiter = Waiter<Uri?>()
    if (!exportWait.compareAndSet(null, waiter)) return JSONObject().put("ok", false).put("error", "busy")
    try {
      host.runOnUiThread { try { launcher.launch(intent) } catch (_: Exception) { exportWait.getAndSet(null)?.finish(null) } }
      val uri = waiter.await() ?: return JSONObject().put("ok", false).put("error", "cancel")
      host.contentResolver.openOutputStream(uri, "wt").use { output ->
        requireNotNull(output) { "document cannot be written" }
        file.inputStream().use { it.copyTo(output) }
      }
      return JSONObject().put("ok", true)
    } finally { exportWait.compareAndSet(waiter, null) }
  }

  private fun openSettings(input: JSONObject): JSONObject {
    val host = activity ?: throw IllegalStateException("shell activity missing")
    val target = input.optString("target", "app")
    val intent = when (target) {
      "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName)
      "exactAlarms" -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        .setData(Uri.parse("package:${host.packageName}"))
      else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${host.packageName}"))
    }
    host.runOnUiThread { host.startActivity(intent) }
    Log.i(TAG, "open settings $target")
    return JSONObject().put("ok", true).put("target", target)
  }

  private fun authenticate(input: JSONObject): JSONObject {
    val host = activity ?: throw IllegalStateException("shell activity missing")
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      return JSONObject().put("ok", false).put("error", "unsupported")
    }
    val latch = CountDownLatch(1)
    val out = JSONObject()
    val once = java.util.concurrent.atomic.AtomicBoolean(false)
    fun finish(ok: Boolean, error: String?) {
      if (!once.compareAndSet(false, true)) return
      out.put("ok", ok)
      if (error != null) out.put("error", error)
      latch.countDown()
    }
    host.runOnUiThread {
      val prompt = BiometricPrompt.Builder(host)
        .setTitle(input.optString("title", "验证身份"))
        .setSubtitle(input.optString("subtitle", ""))
        .setNegativeButton("取消", host.mainExecutor) { _, _ -> finish(false, "cancel") }
        .build()
      prompt.authenticate(
        CancellationSignal(),
        host.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            finish(true, null)
          }

          override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            finish(false, errString.toString())
          }
        },
      )
    }
    if (!latch.await(60, TimeUnit.SECONDS)) finish(false, "timeout")
    return out
  }

  private fun location(input: JSONObject): JSONObject {
    val host = activity ?: throw IllegalStateException("shell activity missing")
    val fine = input.optString("accuracy", "fine") != "coarse"
    val permission = if (fine) Manifest.permission.ACCESS_FINE_LOCATION else Manifest.permission.ACCESS_COARSE_LOCATION
    if (!granted(host, permission)) {
      return JSONObject().put("ok", false).put("error", "permission")
    }
    val manager = host.getSystemService(LocationManager::class.java)
      ?: throw IllegalStateException("LocationManager unavailable")
    val provider = if (fine) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
    val latch = CountDownLatch(1)
    val out = JSONObject()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      manager.getCurrentLocation(provider, null, host.mainExecutor) { location ->
        if (location == null) out.put("ok", false).put("error", "unavailable")
        else out.put("ok", true).put("lat", location.latitude).put("lng", location.longitude).put("accuracy", location.accuracy)
        latch.countDown()
      }
    } else {
      @Suppress("DEPRECATION")
      manager.requestSingleUpdate(provider, { location ->
        out.put("ok", true).put("lat", location.latitude).put("lng", location.longitude).put("accuracy", location.accuracy)
        latch.countDown()
      }, host.mainLooper)
    }
    if (!latch.await(20, TimeUnit.SECONDS)) return JSONObject().put("ok", false).put("error", "timeout")
    return out
  }

  private fun pick(input: JSONObject): JSONObject {
    val host = activity ?: throw IllegalStateException("shell activity missing")
    val launcher = pickLauncher ?: throw IllegalStateException("pick launcher missing")
    val mime = input.optString("mime").ifBlank { when (input.optString("kind", "file")) {
      "image" -> "image/*"
      "video" -> "video/*"
      "audio" -> "audio/*"
      else -> "*/*"
    } }
    val waiter = Waiter<Uri?>()
    if (!pickWait.compareAndSet(null, waiter)) throw IllegalStateException("pick busy")
    try {
      host.runOnUiThread { try { launcher.launch(mime) } catch (_: Exception) { pickWait.getAndSet(null)?.finish(null) } }
      val uri = waiter.await() ?: return JSONObject().put("ok", false).put("error", "cancel")
      val file = copyToCache(host, uri)
      val name = host.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
      } ?: file.name
      return JSONObject().put("ok", true).put("path", file.absolutePath).put("name", name)
    } finally { pickWait.compareAndSet(waiter, null) }
  }

  private fun capturePhoto(): JSONObject {
    val host = activity ?: throw IllegalStateException("shell activity missing")
    if (!granted(host, Manifest.permission.CAMERA)) {
      return JSONObject().put("ok", false).put("error", "permission")
    }
    val launcher = captureLauncher ?: throw IllegalStateException("capture launcher missing")
    val file = File(host.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
    captureFile = file
    val uri = FileProvider.getUriForFile(host, "${host.packageName}.fileprovider", file)
    val waiter = Waiter<Boolean>()
    if (!captureWait.compareAndSet(null, waiter)) throw IllegalStateException("capture busy")
    host.runOnUiThread { launcher.launch(uri) }
    val ok = waiter.await()
    if (!ok) return JSONObject().put("ok", false).put("error", "cancel")
    return JSONObject().put("ok", true).put("path", file.absolutePath)
  }

  private fun installPackage(input: JSONObject): JSONObject {
    val host = activity ?: throw IllegalStateException("shell activity missing")
    val file = ownedFile(host, input.getString("path"))
    val uri = FileProvider.getUriForFile(host, "${host.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW)
      .setDataAndType(uri, "application/vnd.android.package-archive")
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    host.runOnUiThread { host.startActivity(intent) }
    Log.i(TAG, "install package ${file.name}")
    return JSONObject().put("ok", true)
  }

  private fun copyToCache(activity: Activity, uri: Uri): File {
    val name = "pick-${System.currentTimeMillis()}"
    val file = File(activity.cacheDir, name)
    activity.contentResolver.openInputStream(uri).use { input ->
      file.outputStream().use { output -> requireNotNull(input) { "document cannot be read" }.copyTo(output) }
    }
    return file
  }

  private fun ownedFile(activity: Activity, path: String): File {
    val file = File(path)
    val root = file.canonicalFile
    val files = activity.filesDir.canonicalFile
    val cache = activity.cacheDir.canonicalFile
    if (!root.path.startsWith(files.path + File.separator) && !root.path.startsWith(cache.path + File.separator)) {
      throw IllegalArgumentException("path must stay inside app storage")
    }
    if (!root.isFile) throw IllegalArgumentException("file missing")
    return root
  }

  private fun granted(context: android.content.Context, permission: String): Boolean {
    if (permission == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
  }

  private class Waiter<T> {
    private val latch = CountDownLatch(1)
    private val value = AtomicReference<T?>(null)

    fun finish(result: T) {
      value.set(result)
      latch.countDown()
    }

    fun await(): T {
      if (!latch.await(60, TimeUnit.SECONDS)) throw IllegalStateException("timed out")
      return value.get() as T
    }
  }
}
