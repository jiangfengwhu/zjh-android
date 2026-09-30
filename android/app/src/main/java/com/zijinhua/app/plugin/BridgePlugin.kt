package com.zijinhua.app.plugin

fun interface PluginHandler {
  fun invoke(payload: String): String
}

interface PluginRegistry {
  fun register(pluginId: String, method: String, handler: PluginHandler)
}

interface BridgeHost {
  fun getContext(): android.content.Context
}

interface BridgePlugin {
  val id: String

  fun install(registry: PluginRegistry, host: BridgeHost)
}
