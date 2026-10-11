package com.zijinhua.app.shell

import android.content.Context
import com.facebook.react.bridge.Arguments
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.zijinhua.app.MainApplication
import org.json.JSONObject

object ShellNativeEvents {
  /** Emits only to an existing React instance; never boots JS for a background event. */
  fun emit(context: Context, name: String, value: JSONObject) {
    val react = (context.applicationContext as MainApplication).existingReactContext() ?: return
    if (!react.hasActiveReactInstance()) return
    runCatching { react.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name,
      Arguments.createMap().apply { putString("payload", value.toString()) }) }
  }
}
