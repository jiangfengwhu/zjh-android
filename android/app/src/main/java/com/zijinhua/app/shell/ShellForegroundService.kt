package com.zijinhua.app.shell

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zijinhua.app.R

class ShellForegroundService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      Log.i(TAG, "foreground stopped")
      return START_NOT_STICKY
    }
    val title = intent?.getStringExtra(EXTRA_TITLE) ?: "紫金花"
    val body = intent?.getStringExtra(EXTRA_BODY) ?: ""
    val typeName = intent?.getStringExtra(EXTRA_TYPE) ?: "dataSync"
    val notification = notification(title, body)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTE_ID, notification, typeFlag(typeName))
    } else {
      startForeground(NOTE_ID, notification)
    }
    Log.i(TAG, "foreground started type=$typeName")
    return START_STICKY
  }

  private fun notification(title: String, body: String): Notification {
    return NotificationCompat.Builder(this, ShellAlarms.CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_shell_notify)
      .setContentTitle(title)
      .setContentText(body)
      .setOngoing(true)
      .build()
  }

  companion object {
    const val ACTION_START = "com.zijinhua.app.shell.FG_START"
    const val ACTION_STOP = "com.zijinhua.app.shell.FG_STOP"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_TYPE = "type"
    private const val TAG = "ShellForeground"
    private const val NOTE_ID = 41001

    fun start(context: Context, title: String, body: String, type: String) {
    ShellAlarms.ensureChannel(context)
    val intent = Intent(context, ShellForegroundService::class.java)
      .setAction(ACTION_START)
      .putExtra(EXTRA_TITLE, title)
      .putExtra(EXTRA_BODY, body)
      .putExtra(EXTRA_TYPE, type)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent)
    } else {
      context.startService(intent)
    }
    }

    fun stop(context: Context) {
      val intent = Intent(context, ShellForegroundService::class.java).setAction(ACTION_STOP)
      context.startService(intent)
    }

    fun typeFlag(type: String): Int {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
      return when (type) {
        "location" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        "camera" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        "microphone" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        "mediaPlayback" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        "connectedDevice" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      }
    }
  }
}
