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
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.CancellationException
import java.util.UUID

object ShellBluetooth {
  private val main = Handler(Looper.getMainLooper())
  private data class Pending(val kind: String, val characteristic: BluetoothGattCharacteristic?, val reply: CompletableDeferred<JSONObject>)
  private class Connection(val appId: String, val id: String, val address: String, val context: Context) {
    var gatt: BluetoothGatt? = null
    var connected = false
    var pending: Pending? = null
    val subscriptions = mutableSetOf<String>()
  }
  private val connections = mutableMapOf<String, Connection>() // main looper
  private val ccc = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  private fun characteristicKey(value: BluetoothGattCharacteristic) = "${value.service.uuid}/${value.uuid}"
  private fun event(connection: Connection, type: String, characteristic: BluetoothGattCharacteristic? = null, value: ByteArray? = null) {
    ShellNativeEvents.emit(connection.context, "zijinhua.bluetooth", JSONObject().put("appId", connection.appId).put("connectionId", connection.id).put("type", type).apply {
      if (characteristic != null) put("service", characteristic.service.uuid.toString()).put("characteristic", characteristic.uuid.toString())
      if (value != null) put("data", Base64.encodeToString(value, Base64.NO_WRAP))
    })
  }
  @SuppressLint("MissingPermission")
  private fun close(connection: Connection, error: String = "disconnected") {
    if (connections.remove(connection.id) !== connection) return
    connection.pending?.reply?.complete(ShellOperations.result(error)); connection.pending = null
    runCatching { connection.gatt?.disconnect() }; runCatching { connection.gatt?.close() }; connection.gatt = null
    event(connection, "disconnected")
  }
  fun stopApp(appId: String) { connections.values.filter { it.appId == appId }.toList().forEach { close(it, "cancel") } }
  fun stopAll() { connections.values.toList().forEach { close(it, "cancel") } }
  private fun connection(input: JSONObject): Connection? = connections[input.getString("connectionId")]?.takeIf { it.appId == input.getString("appId") && it.connected }
  private fun find(gatt: BluetoothGatt, input: JSONObject): BluetoothGattCharacteristic? = gatt.getService(UUID.fromString(input.getString("service")))?.getCharacteristic(UUID.fromString(input.getString("characteristic")))
  @Suppress("DEPRECATION")
  @SuppressLint("MissingPermission")
  suspend fun gatt(context: Context, method: String, input: JSONObject): JSONObject {
    if (method == "bluetoothDisconnect") {
      connections[input.getString("connectionId")]?.takeIf { it.appId == input.getString("appId") }?.let { close(it, "cancel") }
      return ShellOperations.result()
    }
    if (!canConnect(context)) return ShellOperations.result("permission")
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return ShellOperations.result("unsupported")
    if (!adapter.isEnabled) return ShellOperations.result("disabled")
    if (method == "bluetoothConnect") return connect(context, input)
    val connection = connection(input) ?: return ShellOperations.result("disconnected")
    val gatt = connection.gatt ?: return ShellOperations.result("disconnected")
    if (method == "bluetoothServices") {
      val services = JSONArray(gatt.services.map { service -> JSONObject().put("uuid", service.uuid.toString()).put("characteristics", JSONArray(service.characteristics.map { characteristic ->
        JSONObject().put("uuid", characteristic.uuid.toString()).put("properties", characteristic.properties).put("descriptors", JSONArray(characteristic.descriptors.map { it.uuid.toString() }))
      })) })
      return ShellOperations.result().put("items", services)
    }
    if (connection.pending != null) return ShellOperations.result("busy")
    val characteristic = find(gatt, input) ?: return ShellOperations.result("not-found")
    val pending = Pending(method, characteristic, CompletableDeferred()); connection.pending = pending
    try {
      val started = when (method) {
        "bluetoothRead" -> if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) false else gatt.readCharacteristic(characteristic)
        "bluetoothWrite" -> {
          val bytes = Base64.decode(input.getString("data"), Base64.NO_WRAP)
          require(bytes.size in 1..512)
          val noResponse = input.optBoolean("withoutResponse")
          val property = if (noResponse) BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE else BluetoothGattCharacteristic.PROPERTY_WRITE
          if (characteristic.properties and property == 0) false else {
            val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(characteristic, bytes, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            else { characteristic.value = bytes; characteristic.writeType = type; gatt.writeCharacteristic(characteristic) }
          }
        }
        "bluetoothSubscribe", "bluetoothUnsubscribe" -> {
          val enabled = method == "bluetoothSubscribe"
          val descriptor = characteristic.getDescriptor(ccc)
          val indicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 && characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
          if (descriptor == null || enabled && characteristic.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) false
          else if (!gatt.setCharacteristicNotification(characteristic, enabled)) false
          else {
            val bytes = if (!enabled) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE else if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(descriptor, bytes) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            else { descriptor.value = bytes; gatt.writeDescriptor(descriptor) }
          }
        }
        else -> false
      }
      if (!started) return ShellOperations.result("unsupported")
      val result = pending.reply.await()
      if (result.optBoolean("ok")) {
        if (method == "bluetoothSubscribe") connection.subscriptions.add(characteristicKey(characteristic))
        if (method == "bluetoothUnsubscribe") connection.subscriptions.remove(characteristicKey(characteristic))
      }
      return result
    } catch (cancel: CancellationException) { close(connection, "cancel"); throw cancel }
    finally { if (connection.pending === pending) connection.pending = null }
  }
  @SuppressLint("MissingPermission")
  private suspend fun connect(context: Context, input: JSONObject): JSONObject {
    val appId = input.getString("appId")
    if (connections.size >= 4 || connections.values.count { it.appId == appId } >= 2) return ShellOperations.result("busy")
    val address = input.getString("address").also { require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(it)) }
    if (connections.values.any { it.appId == appId && it.address == address }) return ShellOperations.result("busy")
    val adapter = context.getSystemService(BluetoothManager::class.java)!!.adapter
    val connection = Connection(appId, UUID.randomUUID().toString(), address, context.applicationContext)
    val pending = Pending("connect", null, CompletableDeferred()); connection.pending = pending; connections[connection.id] = connection
    val callback = object : BluetoothGattCallback() {
      private fun post(gatt: BluetoothGatt, work: () -> Unit) { main.post { if (connections[connection.id] === connection && connection.gatt === gatt) work() } }
      override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) { post(gatt) {
        if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) close(connection)
        else if (newState == BluetoothProfile.STATE_CONNECTED) { if (!gatt.discoverServices()) close(connection, "unavailable") }
      } }
      override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { post(gatt) {
        if (status == BluetoothGatt.GATT_SUCCESS) { connection.connected = true; pending.reply.complete(ShellOperations.result().put("connectionId", connection.id)) }
        else close(connection, "unavailable")
      } }
      private fun complete(gatt: BluetoothGatt, kind: String, characteristic: BluetoothGattCharacteristic, status: Int, bytes: ByteArray? = null) { post(gatt) {
        val request = connection.pending ?: return@post
        if (request.kind != kind || request.characteristic !== characteristic) return@post
        if (status == BluetoothGatt.GATT_SUCCESS && kind == "bluetoothSubscribe") connection.subscriptions.add(characteristicKey(characteristic))
        if (status == BluetoothGatt.GATT_SUCCESS && kind == "bluetoothUnsubscribe") connection.subscriptions.remove(characteristicKey(characteristic))
        request.reply.complete(ShellOperations.result(if (status == BluetoothGatt.GATT_SUCCESS) null else "rejected").apply {
          if (status != BluetoothGatt.GATT_SUCCESS) put("nativeCode", status)
          if (bytes != null && status == BluetoothGatt.GATT_SUCCESS) put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        })
      } }
      @Deprecated("Android 13 uses an immutable callback value")
      override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (Build.VERSION.SDK_INT < 33) { @Suppress("DEPRECATION") val bytes = characteristic.value?.clone(); complete(gatt, "bluetoothRead", characteristic, status, bytes) }
      }
      override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) { complete(gatt, "bluetoothRead", characteristic, status, value.clone()) }
      override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { complete(gatt, "bluetoothWrite", characteristic, status) }
      override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { post(gatt) {
        val kind = connection.pending?.kind ?: return@post
        if (kind in setOf("bluetoothSubscribe", "bluetoothUnsubscribe")) complete(gatt, kind, descriptor.characteristic, status)
      } }
      @Deprecated("Android 13 uses an immutable callback value")
      override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT < 33) { @Suppress("DEPRECATION") val bytes = characteristic.value?.clone() ?: byteArrayOf(); changed(gatt, characteristic, bytes) }
      }
      override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { changed(gatt, characteristic, value.clone()) }
      private fun changed(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) { post(gatt) {
        val enabling = connection.pending?.let { it.kind == "bluetoothSubscribe" && it.characteristic === characteristic } == true
        if ((characteristicKey(characteristic) in connection.subscriptions || enabling) && bytes.size <= 512) event(connection, "value", characteristic, bytes)
      } }
    }
    try {
      connection.gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
      if (connection.gatt == null) { close(connection, "unavailable"); return ShellOperations.result("unavailable") }
      return pending.reply.await()
    } catch (cancel: CancellationException) { close(connection, "cancel"); throw cancel }
      catch (error: Exception) { close(connection, "unavailable"); throw error }
    finally { if (connection.pending === pending) connection.pending = null }
  }
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
