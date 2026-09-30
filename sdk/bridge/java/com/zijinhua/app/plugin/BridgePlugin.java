package com.zijinhua.app.plugin;

public interface BridgePlugin {
  String getId();

  void install(PluginRegistry registry, BridgeHost host);
}
