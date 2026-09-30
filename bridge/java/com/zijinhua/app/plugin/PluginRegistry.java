package com.zijinhua.app.plugin;

public interface PluginRegistry {
  void register(String pluginId, String method, PluginHandler handler);
}
