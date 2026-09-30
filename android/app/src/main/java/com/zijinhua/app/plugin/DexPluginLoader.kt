package com.zijinhua.app.plugin

import android.content.Context
import android.util.Log
import com.facebook.react.uimanager.ViewManager
import com.zijinhua.app.bundle.PluginFile
import dalvik.system.DexClassLoader
import java.io.File

data class DexLoad(val count: Int, val views: List<ViewManager<*, *>>)

class DexPluginLoader(private val context: Context) {
  fun loadInto(registry: PluginRegistry, host: BridgeHost, plugins: List<PluginFile>): DexLoad {
    if (plugins.isEmpty()) {
      Log.i(TAG, "no plugins in the current release")
      return DexLoad(0, emptyList())
    }
    val views = mutableListOf<ViewManager<*, *>>()
    var loaded = 0
    for (plugin in plugins) {
      if (loadOne(plugin, registry, host, views)) loaded += 1
    }
    return DexLoad(loaded, views)
  }

  private fun loadOne(
    plugin: PluginFile,
    registry: PluginRegistry,
    host: BridgeHost,
    views: MutableList<ViewManager<*, *>>,
  ): Boolean {
    return try {
      // 新版 Android 只允许加载只读的代码文件。
      plugin.dex.setReadOnly()
      val optimizedDir = File(context.codeCacheDir, "plugin-dex").apply { mkdirs() }
      val loader = DexClassLoader(
        plugin.dex.absolutePath,
        optimizedDir.absolutePath,
        null,
        context.classLoader,
      )
      val instance = loader.loadClass(plugin.className).getDeclaredConstructor().newInstance()
      if (instance !is BridgePlugin) {
        Log.e(TAG, "${plugin.className} does not implement BridgePlugin")
        return false
      }
      instance.install(registry, host)
      if (instance is ViewManager<*, *>) {
        try {
          Log.i(TAG, "native view ${instance.name} from ${instance.id}")
          views.add(instance)
        } catch (error: Exception) {
          Log.e(TAG, "native view skipped id=${instance.id}", error)
        }
      }
      Log.i(TAG, "loaded plugin id=${instance.id} from ${plugin.dex.name}")
      true
    } catch (error: Exception) {
      Log.e(TAG, "failed to load plugin ${plugin.id}", error)
      false
    }
  }

  private companion object {
    const val TAG = "DexPluginLoader"
  }
}
