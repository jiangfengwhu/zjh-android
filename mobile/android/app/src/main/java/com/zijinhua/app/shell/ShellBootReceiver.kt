package com.zijinhua.app.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ShellBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    try {
      ShellAlarms.reschedule(context.applicationContext)
      Log.i(TAG, "reschedule after $action")
    } catch (error: Exception) {
      Log.i(TAG, "boot reschedule skipped ${error.message}")
    }
  }

  private companion object {
    const val TAG = "ShellBootReceiver"
  }
}
