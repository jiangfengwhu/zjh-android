package com.zijinhua.app.bundle

import android.util.Log
import com.facebook.react.ReactHost
import com.facebook.react.bridge.JSBundleLoader
import com.facebook.react.bridge.ReactContext
import com.facebook.react.interfaces.TaskInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SplitBundle {
  private const val TAG = "SplitBundle"

  fun load(host: ReactHost, file: String, expectedContext: ReactContext? = null, isCurrent: () -> Boolean = { true }) {
    val context = host.currentReactContext ?: throw IllegalStateException("react context missing")
    check(isCurrent() && (expectedContext == null || expectedContext === context)) { "react context changed" }
    val error = AtomicReference<Throwable>()
    val latch = CountDownLatch(1)
    val posted = context.runOnJSQueueThread {
      try {
        check(isCurrent() && host.currentReactContext === context) { "react context changed" }
        val method = host.javaClass.methods.firstOrNull { candidate ->
          candidate.name == "loadBundle" && candidate.parameterTypes.size == 1
        } ?: host.javaClass.declaredMethods.first { candidate ->
          candidate.name.startsWith("loadBundle") && candidate.parameterTypes.size == 1
        }
        method.isAccessible = true
        val loader = JSBundleLoader.createCachedSplitBundleFromNetworkLoader(file, file)
        @Suppress("UNCHECKED_CAST")
        val task = method.invoke(host, loader) as TaskInterface<Boolean>
        task.waitForCompletion()
        val failure = task.getError()
        if (failure != null) throw failure
        Log.i(TAG, "loaded $file")
      } catch (throwable: Throwable) {
        error.set(throwable)
      } finally {
        latch.countDown()
      }
    }
    if (!posted) throw IllegalStateException("js queue is not ready")
    if (!latch.await(30, TimeUnit.SECONDS)) {
      throw IllegalStateException("app bundle load timed out")
    }
    val failure = error.get()
    if (failure != null) throw IllegalStateException(failure.message ?: "app bundle load failed", failure)
  }
}
