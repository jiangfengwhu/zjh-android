package com.zijinhua.app.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ShellAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent == null || intent.action !in listOf(ShellAlarms.ACTION_FIRE, ShellAlarms.ACTION_BUTTON)) return
    val pending = goAsync()
    Thread {
      try {
        if (intent.action == ShellAlarms.ACTION_BUTTON) ShellAlarms.button(context.applicationContext, intent)
        else ShellAlarms.fire(context.applicationContext, intent)
      } catch (error: Exception) {
        Log.i(TAG, "alarm skipped ${error.message}")
      } finally { pending.finish() }
    }.start()
  }

  private companion object {
    const val TAG = "ShellAlarmReceiver"
  }
}
