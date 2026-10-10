package com.zijinhua.app.host

import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.zijinhua.app.codegen.NativePluginHostSpec
import com.zijinhua.app.plugin.PluginCenter
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
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
  override fun invalidate() {
    aiRequests.forEach { ShellAi.cancel(it) }
    ShellAppFeatures.stopAll()
    ShellDocuments.stopAll()
    ShellOperations.stopAll()
    super.invalidate()
  }
  override fun call(pluginId: String, method: String, payload: String, promise: Promise) {
    if (pluginId == "shell" && method in ShellOperations.methods) {
      try { ShellOperations.call(reactApplicationContext, method, JSONObject(payload)) { promise.resolve(it.toString()) } }
      catch (_: Exception) { promise.resolve(JSONObject().put("ok", false).put("error", "unavailable").toString()) }
      return
    }
    if (pluginId == "shell" && method in ShellDocuments.methods) {
      try { ShellDocuments.call(reactApplicationContext, method, JSONObject(payload)) { promise.resolve(it.toString()) } }
      catch (_: Exception) { promise.resolve(JSONObject().put("ok", false).put("error", "unavailable").toString()) }
      return
    }
    if (pluginId == "shell" && method in ShellWidgets.methods) {
      calls.execute { try { promise.resolve(ShellWidgets.call(reactApplicationContext, method, JSONObject(payload)).toString()) } catch (_: Exception) { promise.resolve(JSONObject().put("ok", false).put("error", "unavailable").toString()) } }; return
    }
    if (pluginId == "shell" && method in ShellAppFeatures.methods) {
      val invoke = Runnable {
        try {
          ShellAppFeatures.call(method, JSONObject(payload), { value ->
            if (reactApplicationContext.hasActiveReactInstance()) {
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
          }, { promise.resolve(it.toString()) })
        } catch (error: Exception) { promise.reject("PLUGIN_ERROR", error.message, error) }
      }
      if (method in setOf("saveToGallery", "shortcutAdd")) calls.execute(invoke) else invoke.run()
      return
    }
    if (pluginId == "shell" && method == "prepareApplication") {
      PluginCenter.prepareApplicationAsync(payload, { promise.resolve(it) }, { promise.reject("PLUGIN_ERROR", it.message, it) })
      return
    }
    val executor = when {
      pluginId == "shell" && method in setOf("completeCancel", "cancelApplicationPreparation") -> control
      pluginId == "shell" && method == "complete" -> ai
      pluginId == "shell" && method in setOf("loadApp", "loadApplication") -> evaluation
      else -> calls
    }
    executor.execute {
      try {
        val result = if (pluginId == "shell" && method == "complete") {
          val input = JSONObject(payload)
          val id = input.optString("requestId")
          if (id.isNotBlank()) aiRequests.add(id)
          try {
            ShellAi.complete(reactApplicationContext, input) { delta ->
              if (reactApplicationContext.hasActiveReactInstance()) {
                val event = Arguments.createMap().apply { putString("requestId", id); putString("delta", delta) }
                reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit("zijinhua.ai.text", event)
              }
            }.toString()
          } finally { aiRequests.remove(id) }
        } else PluginCenter.registry.invoke(pluginId, method, payload)
        Log.i(TAG, "call $pluginId.$method bytes=${result.length}")
        promise.resolve(result)
        Log.i(TAG, "resolved $pluginId.$method")
      } catch (error: Exception) {
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
    private val evaluation = Executors.newSingleThreadExecutor()
    private val control = Executors.newSingleThreadExecutor()
  }
}
