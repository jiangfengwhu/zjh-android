package com.zijinhua.app.plugin

import java.util.concurrent.ConcurrentHashMap

class MapPluginRegistry : PluginRegistry {
  private val handlers = ConcurrentHashMap<String, PluginHandler>()

  override fun register(pluginId: String, method: String, handler: PluginHandler) {
    handlers["$pluginId:$method"] = handler
  }

  fun invoke(pluginId: String, method: String, payload: String): String {
    val handler = handlers["$pluginId:$method"]
      ?: throw NoSuchElementException("missing plugin method $pluginId.$method")
    return handler.invoke(payload)
  }

  fun methodCount(): Int = handlers.size

  fun clear() {
    handlers.clear()
  }
}
