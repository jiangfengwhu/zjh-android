package com.zijinhua.app.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ShellAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent == null || intent.action !in listOf(ShellAlarms.ACTION_FIRE, ShellAlarms.ACTION_BUTTON)) return
    val pending = goAsync()
    ShellAlarmWork.execute {
      try {
        if (intent.action == ShellAlarms.ACTION_BUTTON) ShellAlarms.button(context.applicationContext, intent)
        else ShellAlarms.fire(context.applicationContext, intent)
      } catch (error: Exception) {
        Log.i(TAG, "alarm skipped ${error.message}")
      } finally { pending.finish() }
    }
  }

  private companion object {
    const val TAG = "ShellAlarmReceiver"
  }
}

/** Alarm operations already serialize their persisted state; share one worker. */
internal object ShellAlarmWork {
  private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "ShellAlarms") }
  fun execute(work: () -> Unit) { executor.execute(work) }
}
