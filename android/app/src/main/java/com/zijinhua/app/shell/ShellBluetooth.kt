package com.zijinhua.app.shell

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.zijinhua.app.plugin.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ShellBluetooth {
  private const val TAG = "ShellBluetooth"

  fun register(registry: PluginRegistry) {
    registry.register("shell", "bluetoothState") { _ -> state().toString() }
    registry.register("shell", "bluetoothDevices") { _ -> devices().toString() }
    registry.register("shell", "bluetoothScan") { payload -> scan(JSONObject(payload.ifBlank { "{}" })).toString() }
  }

  private fun state(): JSONObject {
    val adapter = adapter() ?: return JSONObject().put("ok", true).put("present", false).put("enabled", false)
    return JSONObject().put("ok", true).put("present", true).put("enabled", adapter.isEnabled)
  }

  private fun devices(): JSONObject {
    val context = ShellCapabilities.hostContext()
    if (!canConnect(context)) return JSONObject().put("ok", false).put("error", "permission")
    val adapter = adapter() ?: return JSONObject().put("ok", false).put("error", "unavailable")
    if (!adapter.isEnabled) return JSONObject().put("ok", false).put("error", "disabled")
    val items = JSONArray()
    bonded(adapter).forEach { device ->
      items.put(JSONObject().put("name", deviceName(device)).put("address", device.address ?: ""))
    }
    Log.i(TAG, "bluetooth paired count=${items.length()}")
    return JSONObject().put("ok", true).put("items", items)
  }

  @SuppressLint("MissingPermission")
  private fun scan(input: JSONObject): JSONObject {
    val context = ShellCapabilities.hostContext()
    if (!canScan(context)) return JSONObject().put("ok", false).put("error", "permission")
    val adapter = adapter() ?: return JSONObject().put("ok", false).put("error", "unavailable")
    if (!adapter.isEnabled) return JSONObject().put("ok", false).put("error", "disabled")
    val scanner = adapter.bluetoothLeScanner ?: return JSONObject().put("ok", false).put("error", "scanner")
    val seconds = input.optInt("seconds", 4).coerceIn(1, 8)
    val found = ConcurrentHashMap<String, JSONObject>()
    val names = canConnect(context)
    val callback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        val address = result.device.address ?: return
        if (found.size >= 40 && !found.containsKey(address)) return
        found[address] = JSONObject()
          .put("name", if (names) deviceName(result.device) else "")
          .put("address", address)
          .put("rssi", result.rssi)
      }
    }
    try {
      scanner.startScan(callback)
      CountDownLatch(1).await(seconds.toLong(), TimeUnit.SECONDS)
    } catch (error: SecurityException) {
      Log.i(TAG, "bluetooth scan denied")
      return JSONObject().put("ok", false).put("error", "permission")
    } finally {
      try {
        scanner.stopScan(callback)
      } catch (_: SecurityException) {
      }
    }
    val items = JSONArray()
    found.values.forEach { items.put(it) }
    Log.i(TAG, "bluetooth scan count=${items.length()}")
    return JSONObject().put("ok", true).put("items", items)
  }

  private fun adapter() = ShellCapabilities.hostContext().getSystemService(BluetoothManager::class.java)?.adapter

  @SuppressLint("MissingPermission")
  private fun bonded(adapter: android.bluetooth.BluetoothAdapter): Set<BluetoothDevice> {
    return adapter.bondedDevices ?: emptySet()
  }

  @SuppressLint("MissingPermission")
  private fun deviceName(device: BluetoothDevice): String {
    return try {
      device.name ?: ""
    } catch (_: SecurityException) {
      ""
    }
  }

  private fun canConnect(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < 31) return true
    return granted(context, Manifest.permission.BLUETOOTH_CONNECT)
  }

  private fun canScan(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT >= 31) return granted(context, Manifest.permission.BLUETOOTH_SCAN)
    return granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
  }

  private fun granted(context: android.content.Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
  }
}
