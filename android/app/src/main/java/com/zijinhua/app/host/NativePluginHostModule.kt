package com.zijinhua.app.host

import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.zijinhua.app.codegen.NativePluginHostSpec
import com.zijinhua.app.host.NativeServices
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Callable
import com.facebook.react.bridge.Arguments
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.zijinhua.app.shell.ShellAi
import com.zijinhua.app.shell.ShellOperations
import com.zijinhua.app.shell.ShellDocuments
import com.zijinhua.app.shell.ShellWidgets
import com.zijinhua.app.shell.ShellAppFeatures
import org.json.JSONObject

class NativePluginHostModule(reactContext: ReactApplicationContext) : NativePluginHostSpec(reactContext) {
  init { ShellAppFeatures.attachContext(reactContext.applicationContext) }
  private val aiRequests = ConcurrentHashMap.newKeySet<String>()
  private val tasks = ConcurrentHashMap<FutureTask<Unit>, ExecutorService>()
  private val lifecycle = Any()
  @Volatile private var invalidated = false
  private fun current() = !invalidated
  private fun enqueue(executor: ExecutorService, work: () -> Unit) {
    val task = object : FutureTask<Unit>(Callable { if (current()) work(); Unit }) {
      override fun done() { tasks.remove(this) }
    }
    tasks[task] = executor
    if (!current()) { task.cancel(false); return }
    executor.execute(task)
    // invalidate can race with enqueue; do not retain cancelled queue entries.
    if (!current()) { task.cancel(false); (executor as ThreadPoolExecutor).remove(task) }
  }
  override fun invalidate() {
    synchronized(lifecycle) {
      invalidated = true
      aiRequests.forEach { ShellAi.cancel(it) }
    }
    tasks.entries.toList().forEach { (task, executor) ->
      task.cancel(false)
      (executor as ThreadPoolExecutor).remove(task)
    }
    ShellAppFeatures.stopAll()
    ShellDocuments.stopAll()
    ShellOperations.stopAll()
    super.invalidate()
  }
  override fun call(pluginId: String, method: String, payload: String, promise: Promise) {
    if (!current()) return
    if (pluginId == "shell" && method in ShellOperations.methods) {
      try { ShellOperations.call(reactApplicationContext, method, JSONObject(payload), { if (current()) promise.resolve(it.toString()) }, ::current) }
      catch (_: Exception) { promise.resolve(JSONObject().put("ok", false).put("error", "unavailable").toString()) }
      return
    }
    if (pluginId == "shell" && method in ShellDocuments.methods) {
      try { ShellDocuments.call(reactApplicationContext, method, JSONObject(payload), { if (current()) promise.resolve(it.toString()) }, ::current) }
      catch (_: Exception) { promise.resolve(JSONObject().put("ok", false).put("error", "unavailable").toString()) }
      return
    }
    if (pluginId == "shell" && method in ShellWidgets.methods) {
      enqueue(calls) { try { val result = ShellWidgets.call(reactApplicationContext, method, JSONObject(payload)); if (current()) promise.resolve(result.toString()) } catch (_: Exception) { if (current()) promise.resolve(JSONObject().put("ok", false).put("error", "unavailable").toString()) } }; return
    }
    if (pluginId == "shell" && method in ShellAppFeatures.methods) {
      val invoke = Runnable {
        if (!current()) return@Runnable
        try {
          ShellAppFeatures.call(method, JSONObject(payload), { value ->
            if (current() && reactApplicationContext.hasActiveReactInstance()) {
              val event = Arguments.createMap().apply {
                putString("appId", value.optString("appId")); putString("requestId", value.optString("requestId"))
                putString("kind", value.optString("kind"))
                if (value.has("text")) putString("text", value.getString("text"))
                if (value.has("sensor")) {
                  putString("sensor", value.getString("sensor")); putDouble("timestampMs", value.getDouble("timestampMs")); putInt("accuracy", value.getInt("accuracy"))
                  putArray("values", Arguments.createArray().apply { val values = value.getJSONArray("values"); for (index in 0 until values.length()) pushDouble(values.getDouble(index)) })
                }
              }
              reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit("zijinhua.app.native", event)
            }
          }, { if (current()) promise.resolve(it.toString()) }, ::current)
        } catch (error: Exception) { if (current()) promise.reject("PLUGIN_ERROR", error.message, error) }
      }
      if (method in setOf("saveToGallery", "shortcutAdd")) enqueue(calls) { invoke.run() } else invoke.run()
      return
    }
    if (pluginId == "shell" && method == "prepareApplication") {
      NativeServices.prepareApplicationAsync(payload, { if (current()) promise.resolve(it) }, { if (current()) promise.reject("PLUGIN_ERROR", it.message, it) }, ::current)
      return
    }
    val executor = when {
      pluginId == "shell" && method in setOf("completeCancel", "cancelApplicationPreparation") -> control
      pluginId == "shell" && method == "complete" -> ai
      pluginId == "shell" && method in setOf("loadApp", "loadApplication") -> evaluation
      else -> calls
    }
    enqueue(executor) {
      try {
        val result = if (pluginId == "shell" && method == "complete") {
          val input = JSONObject(payload)
          val id = input.optString("requestId")
          synchronized(lifecycle) {
            if (!current()) return@enqueue
            if (id.isNotBlank()) aiRequests.add(id)
          }
          try {
            ShellAi.complete(reactApplicationContext, input) { delta ->
              if (current() && reactApplicationContext.hasActiveReactInstance()) {
                val event = Arguments.createMap().apply { putString("requestId", id); putString("delta", delta) }
                reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit("zijinhua.ai.text", event)
              }
            }.toString()
          } finally { aiRequests.remove(id) }
        } else if (pluginId == "shell" && method == "loadApplication") {
          NativeServices.loadApplication(payload, reactApplicationContext, ::current)
        } else if (pluginId == "shell" && method == "loadApp") {
          try { NativeServices.loadApp(reactApplicationContext, ::current) }
          catch (error: Exception) { if (current()) NativeServices.reportStartupFailure(); throw error }
        } else NativeServices.registry.invoke(pluginId, method, payload)
        if (!current()) return@enqueue
        Log.i(TAG, "call $pluginId.$method bytes=${result.length}")
        promise.resolve(result)
        Log.i(TAG, "resolved $pluginId.$method")
      } catch (error: Exception) {
        if (!current()) return@enqueue
        Log.e(TAG, "call failed $pluginId.$method", error)
        promise.reject("PLUGIN_ERROR", error.message, error)
      }
    }
  }

  companion object {
    const val NAME = "NativePluginHost"
    private const val TAG = "NativePluginHost"
    private val calls = Executors.newFixedThreadPool(4)
    private val ai = Executors.newFixedThreadPool(4)
    private val evaluation = Executors.newFixedThreadPool(1)
    private val control = Executors.newFixedThreadPool(1)
  }
}
