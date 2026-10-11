package com.zijinhua.app.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ShellBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
    val pending = goAsync()
    ShellAlarmWork.execute {
      try {
        ShellAlarms.reschedule(context.applicationContext)
        Log.i(TAG, "reschedule after $action")
      } catch (error: Exception) {
        Log.i(TAG, "boot reschedule skipped ${error.message}")
      } finally { pending.finish() }
    }
  }

  private companion object {
    const val TAG = "ShellBootReceiver"
  }
}
