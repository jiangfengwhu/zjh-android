package com.zijinhua.app

import android.app.Application
import android.util.Log
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.common.build.ReactBuildConfig
import com.facebook.react.common.assets.ReactFontManager
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.zijinhua.app.bundle.ReleaseCheck
import com.zijinhua.app.bundle.ReleaseStore
import com.zijinhua.app.host.PluginHostPackage
import com.zijinhua.app.plugin.PluginCenter

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    val bundlePath = if (ReactBuildConfig.DEBUG) {
      Log.i(TAG, "debug uses Metro, skip active bundle file")
      null
    } else {
      ReleaseStore(this).vendorPath()
    }
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // Packages that cannot be autolinked yet can be added manually here, for example:
          // add(MyReactNativePackage())
          add(PluginHostPackage())
        },
      jsBundleFilePath = bundlePath,
    )
  }

  override fun onCreate() {
    super.onCreate()
    ReactFontManager.getInstance().addCustomFont(this, "Fusion Pixel 12px Proportional SC", R.font.fusion_pixel)
    if (!ReactBuildConfig.DEBUG) {
      ReleaseStore(this).prepareBoot()
    }
    PluginCenter.install(this)
    ReleaseCheck.ensure(this)
    loadReactNative(this)
    reactHost.start()
    Log.i(TAG, "shell started")
  }

  private companion object {
    const val TAG = "MainApplication"
  }
}
