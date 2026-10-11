package com.zijinhua.app.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.ReactContext
import com.zijinhua.app.MainApplication
import com.zijinhua.app.bundle.BundleInventory
import com.zijinhua.app.bundle.ReleaseStore
import com.zijinhua.app.bundle.SplitBundle
import com.zijinhua.app.bundle.ApplicationStore
import com.zijinhua.app.shell.ShellAi
import com.zijinhua.app.shell.ShellCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object NativeServices {
  private const val TAG = "NativeServices"
  private val io = Executors.newSingleThreadExecutor()
  private val downloads = Executors.newFixedThreadPool(3)
  private var appContext: Context? = null
  private var appContextId = 0
  var onBundleLoading: ((Boolean) -> Unit)? = null
  var onStartupFailure: (() -> Unit)? = null
  @Volatile var startupFailed = false
    private set

  fun reportStartupFailure() {
    startupFailed = true
    Handler(Looper.getMainLooper()).post { onStartupFailure?.invoke() }
  }

  fun setBundleLoading(loading: Boolean) {
    startupFailed = false
    Log.i(TAG, "bundle loading $loading")
    val callback = onBundleLoading ?: return
    Handler(Looper.getMainLooper()).post { callback(loading) }
  }

  val registry = NativeMethods()

  fun install(context: Context) {
    appContext = context.applicationContext
    registerBuiltins(context.applicationContext)
    Log.i(TAG, "services ready methods=${registry.methodCount()}")
  }

  fun prepareApplicationAsync(payload: String, complete: (String) -> Unit, fail: (Exception) -> Unit, isCurrent: () -> Boolean = { true }) {
    downloads.execute {
      if (!isCurrent()) return@execute
      try {
        val context = appContext ?: throw IllegalStateException("native services not installed")
        ApplicationStore(context).prepare(JSONObject(payload))
        complete(JSONObject().put("ok", true).toString())
      } catch (error: Exception) { fail(error) }
    }
  }

  fun loadApplication(payload: String, expectedContext: ReactContext? = null, isCurrent: () -> Boolean = { true }): String {
    val context = appContext ?: throw IllegalStateException("native services not installed")
    val host = (context.applicationContext as MainApplication).reactHost
    val target = expectedContext ?: host.currentReactContext ?: throw IllegalStateException("react context missing")
    check(isCurrent() && host.currentReactContext === target) { "react context changed" }
    val manifest = JSONObject(payload)
    val prepared = ApplicationStore(context).prepare(manifest)
    SplitBundle.load(host, prepared.file.absolutePath, target, isCurrent)
    return JSONObject().put("ok", true).put("assets", JSONObject(prepared.assets)).put("releaseId", manifest.getString("releaseId")).toString()
  }

  private fun registerBuiltins(context: Context) {
    registry.register("shell", "ping", NativeHandler { _ ->
      """{"ok":true,"host":"1.0"}"""
    })
    registry.register("shell", "packages", NativeHandler { _ ->
      BundleInventory(context).toJson()
    })
    registry.register("shell", "bundleReady", NativeHandler { _ ->
      setBundleLoading(false)
      ReleaseStore(context).markHealthy()
      (context.applicationContext as MainApplication).onShellReady()
      """{"ok":true}"""
    })
    registry.register("shell", "loadApp", NativeHandler { _ ->
      try {
        loadApp()
      } catch (error: Exception) {
        reportStartupFailure()
        throw error
      }
    })
    registry.register("shell", "loadApplication", NativeHandler { payload ->
      loadApplication(payload)
    })
    registry.register("shell", "applicationSeed", NativeHandler { payload ->
      val context = appContext ?: throw IllegalStateException("native services not installed")
      JSONObject().put("manifest", ApplicationStore(context).seed(JSONObject(payload).getString("appId"))).toString()
    })
    registry.register("shell", "prepareApplication", NativeHandler { payload ->
      val context = appContext ?: throw IllegalStateException("native services not installed")
      val manifest = JSONObject(payload)
      ApplicationStore(context).prepare(manifest)
      JSONObject().put("ok", true).toString()
    })
    registry.register("shell", "cancelApplicationPreparation", NativeHandler { payload ->
      val context = appContext ?: throw IllegalStateException("native services not installed")
      val input = JSONObject(payload)
      ApplicationStore(context).cancelPreparation(input.getString("appId"), input.getString("releaseId"))
      JSONObject().put("ok", true).toString()
    })
    registry.register("shell", "reloadApplications", NativeHandler { _ ->
      val context = appContext ?: throw IllegalStateException("native services not installed")
      appContextId = 0
      setBundleLoading(true)
      (context.applicationContext as MainApplication).reactHost.reload("application-update")
      JSONObject().put("ok", true).toString()
    })
    registry.register("shell", "checkUpdates", NativeHandler { _ ->
      Log.i(TAG, "check updates")
      checkUpdates()
    })
    registry.register("shell", "applyUpdates", NativeHandler { _ ->
      Log.i(TAG, "apply staged updates")
      applyStagedUpdates()
    })
    ShellCapabilities.register(registry)
    ShellAi.register(registry, context)
    Log.i(TAG, "builtins registered for ${context.packageName}")
  }

  private fun checkUpdates(): String {
    val task = io.submit<String> { checkOnIo() }
    try { return task.get(300, TimeUnit.SECONDS) }
    catch (error: Exception) { task.cancel(true); throw error }
  }

  private fun checkOnIo(): String {
    val context = appContext ?: throw IllegalStateException("native services not installed")
    try {
      ReleaseStore(context).stage()
    } catch (error: Exception) {
      Log.i(TAG, "release skipped ${error.message}")
    }
    return pendingJson(context)
  }

  private fun pendingJson(context: Context): String {
    val items = JSONArray()
    val pending = ReleaseStore(context).pendingItem()
    if (pending != null) items.put(pending)
    Log.i(TAG, "pending updates count=${items.length()}")
    return JSONObject().put("ok", true).put("updated", false).put("items", items).toString()
  }

  private fun applyStagedUpdates(): String {
    val context = appContext ?: throw IllegalStateException("native services not installed")
    val applied = if (ReleaseStore(context).promoteIfReady()) 1 else 0
    Log.i(TAG, "staged updates applied=$applied")
    if (applied == 0) return JSONObject().put("ok", true).put("applied", 0).toString()
    appContextId = 0
    setBundleLoading(true)
    (context.applicationContext as MainApplication).reactHost.reload("package-update")
    return JSONObject().put("ok", true).put("applied", applied).toString()
  }

  /** vendor 已经作为主包在运行，这里把 app 包加载进来。同一个 React 上下文只加载一次。 */
  fun loadApp(expectedContext: ReactContext? = null, isCurrent: () -> Boolean = { true }): String {
    val context = appContext ?: throw IllegalStateException("native services not installed")
    val host = (context.applicationContext as MainApplication).reactHost
    val reactContext = host.currentReactContext ?: throw IllegalStateException("react context missing")
    check(isCurrent() && (expectedContext == null || expectedContext === reactContext)) { "react context changed" }
    val identity = System.identityHashCode(reactContext)
    if (appContextId == identity) {
      Log.i(TAG, "app already loaded")
      return """{"ok":true,"loaded":false}"""
    }
    val file = ReleaseStore(context).appFile()
    Log.i(TAG, "load app bytes=${file.length()}")
    SplitBundle.load(host, file.absolutePath, reactContext, isCurrent)
    appContextId = identity
    return """{"ok":true,"loaded":true}"""
  }
}
