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
import org.json.JSONObject

class NativePluginHostModule(reactContext: ReactApplicationContext) : NativePluginHostSpec(reactContext) {
  private val aiRequests = ConcurrentHashMap.newKeySet<String>()
  override fun invalidate() {
    aiRequests.forEach { ShellAi.cancel(it) }
    super.invalidate()
  }
  override fun call(pluginId: String, method: String, payload: String, promise: Promise) {
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
