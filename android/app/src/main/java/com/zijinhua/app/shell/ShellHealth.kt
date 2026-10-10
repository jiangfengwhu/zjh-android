package com.zijinhua.app.shell

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.coroutines.resume

object ShellHealth {
  private val permissionsByKind = mapOf(
    "steps" to HealthPermission.getReadPermission(StepsRecord::class),
    "heartRate" to HealthPermission.getReadPermission(HeartRateRecord::class),
    "sleep" to HealthPermission.getReadPermission(SleepSessionRecord::class))
  private var host: ComponentActivity? = null
  private var launcher: ActivityResultLauncher<Set<String>>? = null
  private var pending: CancellableContinuation<Set<String>>? = null
  fun attach(activity: ComponentActivity) {
    host = activity
    launcher = activity.registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
      val waiter = pending; pending = null
      if (waiter?.isActive == true) waiter.resume(granted)
    }
  }
  fun onResume(activity: ComponentActivity) {
    if (host === activity && pending?.isActive == false) pending = null
  }
  fun detach(activity: ComponentActivity) {
    if (host !== activity) return
    pending?.cancel(); pending = null; launcher = null; host = null
  }
  private fun kinds(input: JSONObject): Set<String> {
    val list = input.optJSONArray("kinds") ?: JSONArray(permissionsByKind.keys.toList())
    require(list.length() in 1..3)
    return (0 until list.length()).map { list.getString(it).also { value -> require(permissionsByKind.containsKey(value)) } }.toSet()
  }
  private fun client(context: Context): HealthConnectClient? = if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context) else null
  fun status(context: Context): JSONObject {
    val code = HealthConnectClient.getSdkStatus(context)
    return ShellOperations.result().put("status", when (code) {
      HealthConnectClient.SDK_AVAILABLE -> "available"
      HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
      else -> "unavailable"
    })
  }
  suspend fun permissions(context: Context): JSONObject = withContext(ShellOperations.providerIo) {
    val health = client(context) ?: return@withContext ShellOperations.result("unsupported")
    val granted = health.permissionController.getGrantedPermissions()
    ShellOperations.result().put("granted", JSONArray(permissionsByKind.filterValues { it in granted }.keys.toList()))
  }
  suspend fun request(context: Context, input: JSONObject): JSONObject {
    if (client(context) == null) return ShellOperations.result("unsupported")
    val selected = kinds(input)
    val required = selected.map { permissionsByKind.getValue(it) }.toSet()
    val existing = withContext(ShellOperations.providerIo) { client(context)!!.permissionController.getGrantedPermissions() }
    if (existing.containsAll(required)) return ShellOperations.result().put("granted", JSONArray(selected.toList())).put("grantedAll", true)
    if (pending != null) return ShellOperations.result("busy")
    val activityLauncher = launcher ?: return ShellOperations.result("unavailable")
    val granted = suspendCancellableCoroutine<Set<String>> { continuation ->
      // Retain the dialog slot until its result arrives, even if the request timed out.
      // This prevents a late result from completing a newer permission request.
      pending = continuation
      try { activityLauncher.launch(required) }
      catch (_: Exception) { pending = null; continuation.resume(emptySet()) }
    }
    return ShellOperations.result().put("granted", JSONArray(permissionsByKind.filterValues { it in granted || it in existing }.keys.toList()))
      .put("grantedAll", (granted + existing).containsAll(required))
  }
  suspend fun summary(context: Context, input: JSONObject): JSONObject = withContext(ShellOperations.providerIo) {
    val health = client(context) ?: return@withContext ShellOperations.result("unsupported")
    val from = input.getLong("fromMs"); val to = input.getLong("toMs")
    require(from >= 0 && to > from && to - from <= 366L * 86400000)
    val selected = kinds(input)
    val granted = health.permissionController.getGrantedPermissions()
    if (!selected.all { permissionsByKind.getValue(it) in granted }) return@withContext ShellOperations.result("permission")
    val range = TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))
    coroutineScope {
      // Independent provider requests overlap; aggregation handles clipping and duplicate sources.
      val totals = async {
        val metrics = buildSet<AggregateMetric<*>> {
          if ("steps" in selected) add(StepsRecord.COUNT_TOTAL)
          if ("sleep" in selected) add(SleepSessionRecord.SLEEP_DURATION_TOTAL)
        }
        if (metrics.isEmpty()) null else health.aggregate(AggregateRequest(metrics = metrics, timeRangeFilter = range))
      }
      val heart = async {
        if ("heartRate" !in selected) return@async null
        var token: String? = null
        var last: HeartRateRecord.Sample? = null
        val seen = mutableSetOf<String>()
        do {
          ensureActive()
          val page = health.readRecords(ReadRecordsRequest(HeartRateRecord::class, range, pageToken = token))
          page.records.forEach { record -> record.samples.forEach { sample ->
            if (sample.time.toEpochMilli() in from until to && (last == null || sample.time > last!!.time)) last = sample
          } }
          token = page.pageToken?.takeIf { it.isNotEmpty() }
          if (token != null) check(seen.add(token!!))
        } while (token != null)
        last
      }
      val aggregate = totals.await(); val last = heart.await()
      ShellOperations.result().apply {
        if ("steps" in selected) put("steps", aggregate?.get(StepsRecord.COUNT_TOTAL) ?: 0L)
        if ("sleep" in selected) put("sleepMinutes", aggregate?.get(SleepSessionRecord.SLEEP_DURATION_TOTAL)?.toMinutes() ?: 0L)
        if ("heartRate" in selected) { put("heartRate", last?.beatsPerMinute ?: JSONObject.NULL); put("heartRateAtMs", last?.time?.toEpochMilli() ?: JSONObject.NULL) }
      }
    }
  }
}
