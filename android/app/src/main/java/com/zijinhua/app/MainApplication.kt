package com.zijinhua.app

import android.app.Application
import android.util.Log
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.work.Configuration
import com.zijinhua.app.shell.ShellAlarms
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.common.build.ReactBuildConfig
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.zijinhua.app.bundle.ReleaseCheck
import com.zijinhua.app.bundle.ReleaseStore
import com.zijinhua.app.host.PluginHostPackage
import com.zijinhua.app.plugin.PluginCenter

class MainApplication : Application(), ReactApplication, Configuration.Provider {
  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().build()

  private val maintenanceScheduled = AtomicBoolean(false)
  private val preparationStarted = AtomicBoolean(false)
  @Volatile private var reactReady = CompletableFuture<Unit>()
  private var releasePrepared = false
  private val mainHandler = Handler(Looper.getMainLooper())
  private val maintenance by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "ShellMaintenance") } }

  fun whenReactReady(callback: (Throwable?) -> Unit) {
    // Receivers and WorkManager can start the process without opening a screen.
    // Only a UI request needs the existing bundle/plugin/RN preparation chain.
    if (preparationStarted.compareAndSet(false, true)) prepareReact()
    reactReady.whenComplete { _, error -> mainHandler.post { callback(error) } }
  }

  fun retryReactPreparation() {
    if (!reactReady.isCompletedExceptionally) return
    reactReady = CompletableFuture<Unit>()
    prepareReact()
  }

  fun onShellReady() {
    val initializeServices = maintenanceScheduled.compareAndSet(false, true)
    Handler(Looper.getMainLooper()).postDelayed({
      maintenance.execute {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val started = SystemClock.elapsedRealtime()
        if (initializeServices) {
          runCatching { ReleaseCheck.ensure(this) }.onFailure { Log.w(TAG, "release scheduling failed", it) }
          runCatching { ShellAlarms.reschedule(this) }.onFailure { Log.w(TAG, "alarm restoration failed", it) }
        }
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
    super.onCreate()
    Log.i(TAG, "startup application-create")
  }

  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    maintenance.execute { com.zijinhua.app.shell.ShellWidgets.refreshAll(this) }
  }

  private fun prepareReact() {
    val started = SystemClock.elapsedRealtime()
    fun milestone(stage: String) = Log.i(TAG, "startup $stage elapsedMs=${SystemClock.elapsedRealtime() - started}")
    // Establish the Activity/splash first. Disk verification and DEX loading
    // must finish before RN captures the vendor path and native view managers.
    val startup = Executors.newSingleThreadExecutor { task -> Thread(task, "ShellStartup") }
    startup.execute {
      try {
        if (!releasePrepared) {
          if (!ReactBuildConfig.DEBUG) ReleaseStore(this).prepareBoot()
          releasePrepared = true
        }
        milestone("release-prepared")
        PluginCenter.install(this)
        milestone("services-registered")
        mainHandler.post {
          try {
            loadReactNative(this)
            reactHost.start()
            milestone("react-started")
            reactReady.complete(Unit)
          } catch (error: Exception) {
            Log.e(TAG, "react startup failed", error)
            reactReady.completeExceptionally(error)
          }
        }
      } catch (error: Exception) {
        Log.e(TAG, "startup preparation failed", error)
        reactReady.completeExceptionally(error)
      } finally {
        startup.shutdown()
      }
    }
  }

  private companion object {
    const val TAG = "MainApplication"
  }
}
