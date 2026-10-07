package com.zijinhua.app

import android.app.Application
import android.util.Log
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.work.Configuration
import com.zijinhua.app.shell.ShellAlarms
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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

class MainApplication : Application(), ReactApplication, Configuration.Provider {
  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().build()

  private val maintenanceScheduled = AtomicBoolean(false)
  private val maintenance by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "ShellMaintenance") } }

  fun onShellReady() {
    if (!maintenanceScheduled.compareAndSet(false, true)) return
    Handler(Looper.getMainLooper()).postDelayed({
      maintenance.execute {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val started = SystemClock.elapsedRealtime()
        runCatching { ReleaseCheck.ensure(this) }.onFailure { Log.w(TAG, "release scheduling failed", it) }
        runCatching { ShellAlarms.reschedule(this) }.onFailure { Log.w(TAG, "alarm restoration failed", it) }
        runCatching { ReleaseStore(this).collectUnused() }.onFailure { Log.w(TAG, "release cleanup failed", it) }
        Log.i(TAG, "startup maintenance complete elapsedMs=${SystemClock.elapsedRealtime() - started}")
      }
    }, 1500)
  }

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
    val started = SystemClock.elapsedRealtime()
    fun milestone(stage: String) = Log.i(TAG, "startup $stage elapsedMs=${SystemClock.elapsedRealtime() - started}")
    super.onCreate()
    milestone("application-create")
    ReactFontManager.getInstance().addCustomFont(this, "Fusion Pixel 12px Proportional SC", R.font.fusion_pixel)
    if (!ReactBuildConfig.DEBUG) {
      ReleaseStore(this).prepareBoot()
    }
    milestone("release-prepared")
    PluginCenter.install(this)
    milestone("services-registered")
    loadReactNative(this)
    reactHost.start()
    milestone("react-started")
    Log.i(TAG, "shell started")
  }

  private companion object {
    const val TAG = "MainApplication"
  }
}
