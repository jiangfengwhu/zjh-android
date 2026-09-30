package com.zijinhua.app.host

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager
import com.zijinhua.app.plugin.PluginCenter
class PluginHostPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
    if (name == NativePluginHostModule.NAME) {
      return NativePluginHostModule(reactContext)
    }
    return null
  }

  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
    return PluginCenter.nativeViewManagers()
  }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
    return ReactModuleInfoProvider {
      mapOf(
        NativePluginHostModule.NAME to ReactModuleInfo(
          NativePluginHostModule.NAME,
          NativePluginHostModule::class.java.name,
          false,
          false,
          false,
          true,
        ),
      )
    }
  }
}
