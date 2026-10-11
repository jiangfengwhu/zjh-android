package com.zijinhua.app.shell

import java.util.concurrent.ConcurrentHashMap

/** Host lifecycle plus the SDK's visible application set; prewarmed pages cannot open system UI. */
object ShellVisibility {
  @Volatile var foreground = false
  private val visible = ConcurrentHashMap.newKeySet<String>()
  fun set(appId: String, active: Boolean) { if (active) visible.add(appId) else visible.remove(appId) }
  fun active(appId: String) = foreground && appId in visible
  fun clear() { visible.clear() }
}
