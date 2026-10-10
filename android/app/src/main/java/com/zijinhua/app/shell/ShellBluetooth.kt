package com.zijinhua.app.shell

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object ShellBluetooth {
  private var scanning = false // main looper
  fun state(context: Context): JSONObject {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    if (adapter == null) return ShellOperations.result().put("present", false).put("enabled", false)
    if (!canConnect(context)) return ShellOperations.result("permission")
    return ShellOperations.result().put("present", true).put("enabled", adapter.isEnabled)
  }
  @SuppressLint("MissingPermission")
  suspend fun devices(context: Context): JSONObject = withContext(ShellOperations.providerIo) {
    if (!canConnect(context)) return@withContext ShellOperations.result("permission")
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return@withContext ShellOperations.result("unsupported")
    if (!adapter.isEnabled) return@withContext ShellOperations.result("disabled")
    ShellOperations.result().put("items", JSONArray(adapter.bondedDevices.map { JSONObject().put("name", it.name ?: "").put("address", it.address) }))
  }
  @SuppressLint("MissingPermission")
  suspend fun scan(context: Context, input: JSONObject): JSONObject {
    if (scanning) return ShellOperations.result("busy")
    if (!canScan(context)) return ShellOperations.result("permission")
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return ShellOperations.result("unsupported")
    if (!adapter.isEnabled) return ShellOperations.result("disabled")
    val scanner = adapter.bluetoothLeScanner ?: return ShellOperations.result("unsupported")
    val seconds = input.optInt("seconds", 4).also { require(it in 1..15) }
    val found = ConcurrentHashMap<String, JSONObject>()
    val failed = CompletableDeferred<Int>()
    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    val callback = object : ScanCallback() {
      override fun onScanFailed(errorCode: Int) { failed.complete(errorCode) }
      override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        if (closed.get()) return
        val address = result.device.address
        if (found.size >= 100 && !found.containsKey(address)) return
        found[address] = JSONObject().put("name", result.scanRecord?.deviceName ?: "").put("address", address).put("rssi", result.rssi)
      }
    }
    scanning = true
    try {
      scanner.startScan(callback)
      val error = coroutineScope {
        val timer = async { delay(seconds * 1000L); 0 }
        try { select<Int> { failed.onAwait { it }; timer.onAwait { it } } } finally { timer.cancel() }
      }
      if (error != 0) return ShellOperations.result(when (error) { 1, 5, 6 -> "busy"; 4 -> "unsupported"; else -> "unavailable" }).put("nativeCode", error)
      if (!adapter.isEnabled) return ShellOperations.result("disabled")
      return ShellOperations.result().put("items", JSONArray(found.values.sortedByDescending { it.optInt("rssi") }))
    } finally { closed.set(true); runCatching { scanner.stopScan(callback) }; scanning = false }
  }
  private fun canConnect(context: Context) = Build.VERSION.SDK_INT < 31 || granted(context, Manifest.permission.BLUETOOTH_CONNECT)
  private fun canScan(context: Context) = granted(context, if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION)
  private fun granted(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
