package com.zijinhua.app.shell

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zijinhua.app.R
import org.json.JSONArray
import org.json.JSONObject

object ShellAlarms {
  const val ACTION_FIRE = "com.zijinhua.app.shell.ALARM"
  const val EXTRA_ID = "id"
  const val EXTRA_TITLE = "title"
  const val EXTRA_BODY = "body"
  const val CHANNEL_ID = "zijinhua.shell"

  private const val TAG = "ShellAlarms"
  private const val PREFS = "zijinhua.shell.alarms"
  private const val KEY = "entries"

  @Synchronized
  fun schedule(context: Context, id: String, title: String, body: String, atMs: Long): JSONObject {
    val safeId = requireId(id)
    val trigger = atMs.coerceAtLeast(System.currentTimeMillis() + 500L)
    persist(context, safeId, title, body, trigger)
    val exact = arm(context, safeId, title, body, trigger)
    Log.i(TAG, "scheduled id=$safeId atMs=$trigger exact=$exact")
    return JSONObject()
      .put("ok", true)
      .put("id", safeId)
      .put("atMs", trigger)
      .put("exact", exact)
  }

  @Synchronized
  fun cancel(context: Context, id: String): JSONObject {
    val safeId = requireId(id)
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val pending = pending(context, safeId, "", "")
    alarmManager?.cancel(pending)
    pending.cancel()
    val left = read(context).filter { it.getString("id") != safeId }
    write(context, left)
    NotificationManagerCompat.from(context).cancel(notificationId(safeId))
    Log.i(TAG, "cancelled id=$safeId")
    return JSONObject().put("ok", true).put("id", safeId)
  }

  @Synchronized
  fun list(context: Context): JSONObject {
    val items = JSONArray()
    read(context).forEach { items.put(it) }
    return JSONObject().put("ok", true).put("items", items)
  }

  fun show(context: Context, id: String, title: String, body: String) {
    ensureChannel(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_shell_notify)
      .setContentTitle(title.ifBlank { "紫金花" })
      .setContentText(body)
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .build()
    NotificationManagerCompat.from(context).notify(notificationId(id), notification)
    Log.i(TAG, "notification posted id=$id")
  }

  @Synchronized
  fun fire(context: Context, intent: Intent) {
    val id = intent.getStringExtra(EXTRA_ID) ?: return
    val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
    val body = intent.getStringExtra(EXTRA_BODY) ?: ""
    show(context, id, title, body)
    val left = read(context).filter { it.getString("id") != id }
    write(context, left)
    Log.i(TAG, "alarm fired id=$id")
  }

  @Synchronized
  fun reschedule(context: Context) {
    val now = System.currentTimeMillis()
    val future = mutableListOf<JSONObject>()
    for (item in read(context)) {
      val atMs = item.getLong("atMs")
      val id = item.getString("id")
      val title = item.optString("title")
      val body = item.optString("body")
      if (atMs <= now) {
        show(context, id, title, body)
      } else {
        arm(context, id, title, body, atMs)
        future.add(item)
      }
    }
    write(context, future)
    Log.i(TAG, "rescheduled count=${future.size}")
  }

  private fun arm(context: Context, id: String, title: String, body: String, trigger: Long): Boolean {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
      ?: throw IllegalStateException("AlarmManager unavailable")
    val pending = pending(context, id, title, body)
    val exact = canExact(alarmManager)
    if (exact) {
      alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    } else {
      alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    }
    return exact
  }

  private fun pending(context: Context, id: String, title: String, body: String): PendingIntent {
    val intent = Intent(context, ShellAlarmReceiver::class.java).setAction(ACTION_FIRE)
    intent.putExtra(EXTRA_ID, id)
    intent.putExtra(EXTRA_TITLE, title)
    intent.putExtra(EXTRA_BODY, body)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(context, notificationId(id), intent, flags)
  }

  private fun persist(context: Context, id: String, title: String, body: String, atMs: Long) {
    val next = read(context).filter { it.getString("id") != id }.toMutableList()
    next.add(JSONObject().put("id", id).put("title", title).put("body", body).put("atMs", atMs))
    write(context, next)
  }

  private fun read(context: Context): List<JSONObject> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
    val array = JSONArray(raw)
    return List(array.length()) { index -> array.getJSONObject(index) }
  }

  private fun write(context: Context, items: List<JSONObject>) {
    val array = JSONArray()
    items.forEach { array.put(it) }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).commit()
  }

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val channel = NotificationChannel(CHANNEL_ID, "提醒", NotificationManager.IMPORTANCE_HIGH)
    channel.description = "应用定时提醒"
    manager.createNotificationChannel(channel)
  }

  private fun canExact(alarmManager: AlarmManager): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return alarmManager.canScheduleExactAlarms()
  }

  fun notificationId(id: String): Int = id.hashCode() and 0x7fffffff

  fun requireId(id: String): String {
    if (!id.matches(Regex("[A-Za-z0-9._:-]{1,80}"))) {
      throw IllegalArgumentException("bad id")
    }
    return id
  }
}
