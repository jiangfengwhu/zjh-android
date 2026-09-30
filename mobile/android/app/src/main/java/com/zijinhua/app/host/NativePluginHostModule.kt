package com.zijinhua.app.host

import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.zijinhua.app.codegen.NativePluginHostSpec
import com.zijinhua.app.plugin.PluginCenter
import java.util.concurrent.Executors

class NativePluginHostModule(reactContext: ReactApplicationContext) : NativePluginHostSpec(reactContext) {
  override fun call(pluginId: String, method: String, payload: String, promise: Promise) {
    calls.execute {
      try {
        val result = PluginCenter.registry.invoke(pluginId, method, payload)
        Log.i(TAG, "call $pluginId.$method bytes=${result.length}")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
          promise.resolve(result)
          Log.i(TAG, "resolved $pluginId.$method")
        }
      } catch (error: Exception) {
        Log.e(TAG, "call failed $pluginId.$method", error)
        promise.reject("PLUGIN_ERROR", error.message, error)
      }
    }
  }

  companion object {
    const val NAME = "NativePluginHost"
    private const val TAG = "NativePluginHost"
    private val calls = Executors.newCachedThreadPool()
  }
}
