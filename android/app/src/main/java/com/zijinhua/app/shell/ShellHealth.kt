package com.zijinhua.app.shell

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.zijinhua.app.plugin.PluginRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ShellHealth {
  private const val TAG = "ShellHealth"
  private val readPermissions = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
  )
  private var launcher: ActivityResultLauncher<Set<String>>? = null
  private val wait = AtomicReference<Waiter?>(null)

  fun attach(host: ComponentActivity) {
    val contract = PermissionController.createRequestPermissionResultContract()
    launcher = host.registerForActivityResult(contract) { granted ->
      wait.getAndSet(null)?.finish(granted)
    }
  }

  fun register(registry: PluginRegistry) {
    registry.register("shell", "healthStatus") { _ -> status().toString() }
    registry.register("shell", "healthPermissions") { _ -> permissions().toString() }
    registry.register("shell", "requestHealth") { _ -> request().toString() }
    registry.register("shell", "healthSummary") { payload -> summary(JSONObject(payload)).toString() }
  }

  private fun status(): JSONObject {
    val context = ShellCapabilities.hostContext()
    val code = HealthConnectClient.getSdkStatus(context)
    val name = when (code) {
      HealthConnectClient.SDK_AVAILABLE -> "available"
      HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
      else -> "unavailable"
    }
    Log.i(TAG, "health status $name")
    return JSONObject().put("ok", name == "available").put("status", name)
  }

  private fun client(): HealthConnectClient? {
    val context = ShellCapabilities.hostContext()
    if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return null
    return HealthConnectClient.getOrCreate(context)
  }

  private fun permissions(): JSONObject {
    val health = client() ?: return JSONObject().put("ok", false).put("error", "unavailable")
    val granted = runBlocking { health.permissionController.getGrantedPermissions() }
    val items = JSONArray()
    readPermissions.filter { granted.contains(it) }.forEach { items.put(it) }
    return JSONObject().put("ok", true).put("granted", items)
  }

  private fun request(): JSONObject {
    if (client() == null) return JSONObject().put("ok", false).put("error", "unavailable")
    val host = launcher ?: return JSONObject().put("ok", false).put("error", "launcher")
    val waiter = Waiter()
    if (!wait.compareAndSet(null, waiter)) return JSONObject().put("ok", false).put("error", "busy")
    val activity = ShellCapabilities.hostActivity()
    if (activity == null) {
      wait.set(null)
      return JSONObject().put("ok", false).put("error", "activity")
    }
    activity.runOnUiThread { host.launch(readPermissions) }
    val granted = waiter.await()
    val ok = granted.containsAll(readPermissions)
    Log.i(TAG, "health permission granted=$ok")
    return JSONObject().put("ok", true).put("granted", ok)
  }

  private fun summary(input: JSONObject): JSONObject {
    val health = client() ?: return JSONObject().put("ok", false).put("error", "unavailable")
    val from = Instant.ofEpochMilli(input.getLong("fromMs"))
    val to = Instant.ofEpochMilli(input.getLong("toMs"))
    val range = TimeRangeFilter.between(from, to)
    val granted = runBlocking { health.permissionController.getGrantedPermissions() }
    if (!granted.containsAll(readPermissions)) {
      return JSONObject().put("ok", false).put("error", "permission")
    }
    val steps = runBlocking {
      health.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records.sumOf { it.count }
    }
    val heart = runBlocking {
      health.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records
        .flatMap { it.samples }
        .maxByOrNull { it.time }
        ?.beatsPerMinute ?: 0L
    }
    val sleep = runBlocking {
      health.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records.sumOf { record ->
        java.time.Duration.between(record.startTime, record.endTime).toMinutes()
      }
    }
    Log.i(TAG, "health summary steps=$steps")
    return JSONObject()
      .put("ok", true)
      .put("steps", steps)
      .put("heartRate", heart)
      .put("sleepMinutes", sleep)
  }

  private class Waiter {
    private val latch = CountDownLatch(1)
    private val value = AtomicReference<Set<String>>(emptySet())

    fun finish(result: Set<String>) {
      value.set(result)
      latch.countDown()
    }

    fun await(): Set<String> {
      if (!latch.await(60, TimeUnit.SECONDS)) throw IllegalStateException("timed out")
      return value.get()
    }
  }
}
