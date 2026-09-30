package com.zijinhua.app.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ShellAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != ShellAlarms.ACTION_FIRE) return
    try {
      ShellAlarms.fire(context.applicationContext, intent)
    } catch (error: Exception) {
      Log.i(TAG, "alarm skipped ${error.message}")
    }
  }

  private companion object {
    const val TAG = "ShellAlarmReceiver"
  }
}
