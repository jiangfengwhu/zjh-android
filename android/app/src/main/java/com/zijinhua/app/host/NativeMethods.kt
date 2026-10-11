package com.zijinhua.app.host

import java.util.concurrent.ConcurrentHashMap

fun interface NativeHandler {
  fun invoke(payload: String): String
}

/** APK 内置服务的方法表，不接受运行时扩展。 */
class NativeMethods {
  private val handlers = ConcurrentHashMap<String, NativeHandler>()

  fun register(service: String, method: String, handler: NativeHandler) {
    handlers["$service:$method"] = handler
  }

  fun invoke(service: String, method: String, payload: String): String {
    val handler = handlers["$service:$method"]
      ?: throw NoSuchElementException("missing native method $service.$method")
    return handler.invoke(payload)
  }

  fun methodCount(): Int = handlers.size
}
