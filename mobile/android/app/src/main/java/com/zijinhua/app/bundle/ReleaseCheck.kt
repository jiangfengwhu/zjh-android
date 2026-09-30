package com.zijinhua.app.bundle

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.zijinhua.app.MainActivity
import com.zijinhua.app.R
import com.zijinhua.app.shell.ShellAlarms
import java.util.concurrent.TimeUnit

object ReleaseCheck {
  private const val TAG = "ReleaseCheck"
  private const val WORK = "zijinhua.release.check"
  private const val PREFS = "zijinhua.release.check"
  private const val KEY_NOTIFIED = "notified"
  private const val NOTE_ID = 41002

  fun ensure(context: Context) {
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val request = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(30, TimeUnit.MINUTES)
      .setConstraints(constraints)
      .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    Log.i(TAG, "periodic check every 30m")
  }

  fun run(context: Context) {
    val store = ReleaseStore(context)
    store.stage()
    val id = store.pendingItem()?.optString("sha256").orEmpty()
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (id.isEmpty()) {
      if (prefs.contains(KEY_NOTIFIED)) prefs.edit().remove(KEY_NOTIFIED).apply()
      Log.i(TAG, "release current")
      return
    }
    if (prefs.getString(KEY_NOTIFIED, "") == id) {
      Log.i(TAG, "release already notified id=$id")
      return
    }
    notify(context)
    prefs.edit().putString(KEY_NOTIFIED, id).apply()
    Log.i(TAG, "release ready id=$id")
  }

  private fun notify(context: Context) {
    ShellAlarms.ensureChannel(context)
    val open = PendingIntent.getActivity(
      context,
      NOTE_ID,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, ShellAlarms.CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_shell_notify)
      .setContentTitle("有新内容可以加载")
      .setContentText("一整套已经下载到本机")
      .setAutoCancel(true)
      .setContentIntent(open)
      .build()
    try {
      NotificationManagerCompat.from(context).notify(NOTE_ID, notification)
    } catch (error: SecurityException) {
      Log.i(TAG, "release notify skipped ${error.message}")
    }
  }
}

class ReleaseCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  override fun doWork(): Result {
    return try {
      ReleaseCheck.run(applicationContext)
      Result.success()
    } catch (error: Exception) {
      Log.i(TAG, "check failed ${error.message}")
      Result.retry()
    }
  }

  private companion object {
    const val TAG = "ReleaseCheck"
  }
}
