package com.zijinhua.app.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.uimanager.ViewManager
import com.zijinhua.app.MainApplication
import com.zijinhua.app.bundle.BundleInventory
import com.zijinhua.app.bundle.ReleaseStore
import com.zijinhua.app.bundle.SplitBundle
import com.zijinhua.app.shell.ShellAlarms
import com.zijinhua.app.shell.ShellAi
import com.zijinhua.app.shell.ShellBluetooth
import com.zijinhua.app.shell.ShellCapabilities
import com.zijinhua.app.shell.ShellComms
import com.zijinhua.app.shell.ShellHealth
import com.zijinhua.app.shell.ShellNfc
import com.zijinhua.app.shell.ShellPhone
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object PluginCenter {
  private const val TAG = "PluginCenter"
  private val io = Executors.newSingleThreadExecutor()
  private var appContext: Context? = null
  private var appContextId = 0
  var onBundleLoading: ((Boolean) -> Unit)? = null

  fun setBundleLoading(loading: Boolean) {
    Log.i(TAG, "bundle loading $loading")
    val callback = onBundleLoading ?: return
    Handler(Looper.getMainLooper()).post { callback(loading) }
  }

  val registry: MapPluginRegistry = MapPluginRegistry()
  private val nativeViews = mutableListOf<ViewManager<*, *>>()
  private val viewsLock = Any()
  private var viewsBound = false

  fun install(context: Context) {
    appContext = context.applicationContext
    reload()
  }

  fun reload() {
    val context = appContext ?: return
    registry.clear()
    registerBuiltins(context)
    val loaded = DexPluginLoader(context).loadInto(registry, host(context), ReleaseStore(context).plugins())
    acceptNativeViews(loaded.views)
    Log.i(TAG, "plugins ready builtin=shell loaded=${loaded.count} methods=${registry.methodCount()} views=${loaded.views.size}")
  }

  fun nativeViewManagers(): List<ViewManager<*, *>> {
    synchronized(viewsLock) {
      viewsBound = true
      return nativeViews.toList()
    }
  }

  private fun acceptNativeViews(found: List<ViewManager<*, *>>) {
    val accepted = mutableListOf<ViewManager<*, *>>()
    val names = mutableSetOf("JollyVideoView")
    for (manager in found) {
      val name = try {
        manager.name
      } catch (error: Exception) {
        Log.e(TAG, "native view name failed", error)
        continue
      }
      if (name.isBlank() || !names.add(name)) {
        Log.e(TAG, "skip native view name=$name")
        continue
      }
      accepted.add(manager)
    }
    synchronized(viewsLock) {
      nativeViews.clear()
      nativeViews.addAll(accepted)
      if (viewsBound) {
        Log.i(TAG, "native views updated count=${accepted.size}; they apply on next cold start")
      } else {
        Log.i(TAG, "native views ready count=${accepted.size}")
      }
    }
  }

  fun syncPlugins(): String {
    return io.submit<String> { syncOnIo() }.get(60, TimeUnit.SECONDS)
  }

  private fun syncOnIo(): String {
    Log.i(TAG, "plugins stay with the installed release")
    reload()
    return JSONObject().put("ok", true).put("written", 0).toString()
  }

  private fun registerBuiltins(context: Context) {
    registry.register("shell", "ping", PluginHandler { _ ->
      """{"ok":true,"host":"1.0"}"""
    })
    registry.register("shell", "reloadPlugins", PluginHandler { _ ->
      reload()
      """{"ok":true,"methods":${registry.methodCount()}}"""
    })
    registry.register("shell", "syncPlugins", PluginHandler { _ ->
      Log.i(TAG, "sync plugins")
      syncPlugins()
    })
    registry.register("shell", "packages", PluginHandler { _ ->
      BundleInventory(context).toJson()
    })
    registry.register("shell", "bundleReady", PluginHandler { _ ->
      setBundleLoading(false)
      ReleaseStore(context).markHealthy()
      """{"ok":true}"""
    })
    registry.register("shell", "loadApp", PluginHandler { _ ->
      loadApp()
    })
    registry.register("shell", "checkUpdates", PluginHandler { _ ->
      Log.i(TAG, "check updates")
      checkUpdates()
    })
    registry.register("shell", "applyUpdates", PluginHandler { _ ->
      Log.i(TAG, "apply staged updates")
      applyStagedUpdates()
    })
    ShellCapabilities.register(registry)
    ShellPhone.register(registry)
    ShellHealth.register(registry)
    ShellBluetooth.register(registry)
    ShellNfc.register(registry)
    ShellComms.register(registry)
    ShellAi.register(registry, context)
    ShellAlarms.reschedule(context)
    Log.i(TAG, "builtins registered for ${context.packageName}")
  }

  private fun checkUpdates(): String {
    return io.submit<String> { checkOnIo() }.get(300, TimeUnit.SECONDS)
  }

  private fun checkOnIo(): String {
    val context = appContext ?: throw IllegalStateException("plugin center not installed")
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
    val context = appContext ?: throw IllegalStateException("plugin center not installed")
    val applied = if (ReleaseStore(context).promoteIfReady()) 1 else 0
    Log.i(TAG, "staged updates applied=$applied")
    if (applied == 0) return JSONObject().put("ok", true).put("applied", 0).toString()
    reload()
    appContextId = 0
    setBundleLoading(true)
    (context.applicationContext as MainApplication).reactHost.reload("package-update")
    return JSONObject().put("ok", true).put("applied", applied).toString()
  }

  /** vendor 已经作为主包在运行，这里把 app 包加载进来。同一个 React 上下文只加载一次。 */
  private fun loadApp(): String {
    val context = appContext ?: throw IllegalStateException("plugin center not installed")
    val host = (context.applicationContext as MainApplication).reactHost
    val reactContext = host.currentReactContext ?: throw IllegalStateException("react context missing")
    val identity = System.identityHashCode(reactContext)
    if (appContextId == identity) {
      Log.i(TAG, "app already loaded")
      return """{"ok":true,"loaded":false}"""
    }
    val file = ReleaseStore(context).appFile()
    Log.i(TAG, "load app bytes=${file.length()}")
    SplitBundle.load(host, file.absolutePath)
    appContextId = identity
    return """{"ok":true,"loaded":true}"""
  }
  private fun host(context: Context): BridgeHost {
    return object : BridgeHost {
      override fun getContext(): Context = context
    }
  }

}
