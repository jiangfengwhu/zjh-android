package com.zijinhua.app.shell

import android.app.Activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import com.zijinhua.app.host.NativeMethods
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ShellCapabilities {
  private const val TAG = "ShellCapabilities"
  private var activity: ComponentActivity? = null
  fun attach(host: ComponentActivity) {
    activity = host
    ShellFileTransfers.attach(host)
    ShellHealth.attach(host)
    Log.i(TAG, "activity attached")
  }
  fun detach(host: ComponentActivity) {
    if (activity === host) {
      ShellFileTransfers.detach(host)
      ShellHealth.detach(host)
      activity = null
    }
  }

  internal fun hostActivity(): ComponentActivity? = activity

  internal fun hostContext() = appContext()

  fun register(registry: NativeMethods) {
    registry.register("shell", "consumeLaunchLink") { payload ->
      val url = JSONObject(payload).optString("url")
      val host = activity ?: throw IllegalStateException("shell activity missing")
      host.runOnUiThread {
        if (url.isNotEmpty() && host.intent?.dataString == url) {
          host.intent = Intent(host.intent).setAction(Intent.ACTION_MAIN).setData(null)
        }
      }
      JSONObject().put("ok", true).toString()
    }
    registry.register("shell", "capabilities") { _ -> capabilities().toString() }
    registry.register("shell", "cancelAllSchedules") { payload -> ShellAlarms.cancelAll(appContext(), JSONObject(payload).getString("appId")).toString() }
    registry.register("shell", "notificationStatus") { payload ->
      val input = JSONObject(payload)
      ShellAlarms.status(appContext(), input.getString("appId"), input.optString("appName")).toString()
    }
    registry.register("shell", "notificationSettings") { payload ->
      val input = JSONObject(payload)
      ShellAlarms.settings(appContext(), input.optBoolean("exact"), input.getString("appId"), input.optString("appName"))
      JSONObject().put("ok", true).toString()
    }
    registry.register("shell", "notificationEvents") { payload ->
      val input = JSONObject(payload)
      ShellAlarms.notificationEvents(appContext(), input.getString("appId"), input.optJSONArray("ack")).toString()
    }
    registry.register("shell", "notify") { payload -> notify(JSONObject(payload)).toString() }
    registry.register("shell", "schedule") { payload -> schedule(JSONObject(payload)).toString() }
    registry.register("shell", "cancelSchedule") { payload -> cancelSchedule(JSONObject(payload)).toString() }
    registry.register("shell", "schedules") { _ -> schedules().toString() }
    registry.register("shell", "installPackage") { payload -> installPackage(JSONObject(payload)).toString() }
  }

  private fun appContext() = activity?.applicationContext
    ?: throw IllegalStateException("shell activity missing")

  private fun capabilities(): JSONObject {
    val methods = JSONArray()
    listOf(
      "capabilities", "notify", "schedule", "cancelSchedule", "notificationStatus", "notificationSettings", "notificationEvents", "cancelAllSchedules",
      "schedules", "share",
      "installPackage",
      "device", "calendarEvents", "calendarInsert", "openUrl",

      "healthStatus", "healthPermissions", "requestHealth", "healthSummary",
      "bluetoothState", "bluetoothDevices", "bluetoothScan", "nfcStatus", "nfcRead",
      "appearance", "systemBars", "bundleReady", "loadApp", "providers", "saveProvider", "deleteProvider", "complete", "completeCancel",
    ).plus((ShellAppFeatures.methods + ShellDocuments.methods + ShellWidgets.methods + ShellOperations.methods)).toSet().forEach { methods.put(it) }
    return JSONObject().put("ok", true).put("methods", methods)
  }

  private fun notify(input: JSONObject): JSONObject {
    val context = appContext()
    val id = ShellAlarms.requireId(input.getString("id"))
    val options = input.optJSONObject("options") ?: JSONObject()
    val state = ShellAlarms.status(context, id.substringBefore(":").takeIf { id.contains(":") }, options.optString("appName"))
    if (!state.getBoolean("enabled")) return JSONObject().put("ok", false)
      .put("error", if (!state.getBoolean("granted")) "permission" else "channel-disabled")
    ShellAlarms.show(context, id, input.optString("title"), input.optString("body"), input.optJSONObject("options") ?: JSONObject())
    return JSONObject().put("ok", true).put("id", id)
  }

  private fun schedule(input: JSONObject): JSONObject {
    return ShellAlarms.schedule(
      appContext(),
      input.getString("id"),
      input.optString("title"),
      input.optString("body"),
      input.getLong("atMs"),
      input.optJSONObject("options") ?: JSONObject(),
    )
  }

  private fun cancelSchedule(input: JSONObject): JSONObject {
    return ShellAlarms.cancel(appContext(), input.getString("id"))
  }

  private fun schedules(): JSONObject = ShellAlarms.list(appContext())

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

}
