package com.zijinhua.app.shell

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.Settings
import com.zijinhua.app.MainActivity
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
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

  const val ACTION_BUTTON = "com.zijinhua.app.shell.NOTIFICATION_ACTION"

  private fun validate(options: JSONObject) {
    require((options.optJSONObject("params")?.toString()?.toByteArray()?.size ?: 0) <= 4096)
    val actions = options.optJSONArray("actions") ?: JSONArray()
    require(actions.length() <= 3)
    val ids = mutableSetOf<String>()
    for (i in 0 until actions.length()) {
      val action = actions.getJSONObject(i)
      require(action.getString("id").matches(Regex("[A-Za-z0-9._-]{1,40}")) && ids.add(action.getString("id")))
      require(action.getString("title").length in 1..40)
      require(action.optString("behavior", "open") in listOf("open", "snooze", "complete"))
      if (action.optString("behavior") == "snooze") require(action.optLong("snoozeMs", 600000) in 60000..604800000)
    }
    options.optJSONObject("repeat")?.let { rule ->
      require(rule.getInt("hour") in 0..23 && rule.getInt("minute") in 0..59)
      val days = rule.getJSONArray("weekdays")
      require(days.length() in 1..7)
      for (i in 0 until days.length()) require(days.getInt(i) in 1..7)
      if (rule.has("timeZone")) require(TimeZone.getAvailableIDs().contains(rule.getString("timeZone")))
    }
  }

  private fun nextTime(rule: JSONObject, after: Long): Long {
    val zone = if (rule.has("timeZone")) TimeZone.getTimeZone(rule.getString("timeZone")) else TimeZone.getDefault()
    val days = rule.getJSONArray("weekdays")
    val allowed = (0 until days.length()).map { days.getInt(it) }.toSet()
    val date = Calendar.getInstance(zone).apply { timeInMillis = after }
    for (offset in 0..7) {
      val candidate = (date.clone() as Calendar).apply {
        add(Calendar.DATE, offset); set(Calendar.HOUR_OF_DAY, rule.getInt("hour")); set(Calendar.MINUTE, rule.getInt("minute"))
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
      }
      if ((candidate.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1 in allowed && candidate.timeInMillis > after) return candidate.timeInMillis
    }
    error("cannot compute next reminder")
  }

  fun status(context: Context, appId: String? = null, appName: String? = null, rename: Boolean = true): JSONObject {
    val id = ensureChannel(context, appId, appName, rename)
    val granted = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val channelEnabled = Build.VERSION.SDK_INT < 26 ||
      context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
    return JSONObject().put("ok", true).put("granted", granted).put("channelEnabled", channelEnabled)
      .put("enabled", granted && channelEnabled).put("channelId", id)
      .put("exact", canExact(context.getSystemService(AlarmManager::class.java)))
  }

  fun settings(context: Context, exact: Boolean, appId: String? = null, appName: String? = null) {
    val id = ensureChannel(context, appId, appName)
    val intent = if (exact && Build.VERSION.SDK_INT >= 31)
      Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    else if (Build.VERSION.SDK_INT >= 26 && NotificationManagerCompat.from(context).areNotificationsEnabled())
      Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, id)
    else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  @Synchronized
  fun schedule(context: Context, id: String, title: String, body: String, atMs: Long, options: JSONObject = JSONObject()): JSONObject {
    val safeId = requireId(id)
    validate(options)
    ensureChannel(context, safeId.substringBefore(":").takeIf { safeId.contains(":") }, options.optString("appName").takeIf { it.isNotBlank() })
    val trigger = options.optJSONObject("repeat")?.let { nextTime(it, System.currentTimeMillis()) } ?: atMs.coerceAtLeast(System.currentTimeMillis() + 500L)
    persist(context, safeId, title, body, trigger, options)
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
    for (entry in read(context).filter { it.optJSONObject("options")?.optString("parentId") == safeId }) cancel(context, entry.getString("id"))
    val pending = pending(context, safeId, "", "")
    alarmManager?.cancel(pending)
    pending.cancel()
    val left = read(context).filter { it.getString("id") != safeId }
    write(context, left)
    NotificationManagerCompat.from(context).cancel(notificationId(safeId))
    NotificationManagerCompat.from(context).cancel(safeId, 0)
    Log.i(TAG, "cancelled id=$safeId")
    return JSONObject().put("ok", true).put("id", safeId)
  }

  @Synchronized
  fun cancelAll(context: Context, appId: String): JSONObject {
    require(appId.matches(Regex("[a-z][a-z0-9-]{0,63}")))
    val prefix = "$appId:"
    read(context).filter { it.getString("id").startsWith(prefix) }.forEach { cancel(context, it.getString("id")) }
    val manager = context.getSystemService(NotificationManager::class.java)
    manager?.activeNotifications?.filter { it.tag?.startsWith(prefix) == true }?.forEach { manager.cancel(it.tag, it.id) }
    saveEvents(context, events(context).filter { it.optString("appId") != appId })
    return JSONObject().put("ok", true)
  }

  @Synchronized
  fun list(context: Context): JSONObject {
    val items = JSONArray()
    read(context).forEach { items.put(it) }
    return JSONObject().put("ok", true).put("items", items)
  }

  fun show(context: Context, id: String, title: String, body: String, options: JSONObject = JSONObject()) {
    requireId(id)
    validate(options)
    val state = status(context, id.substringBefore(":").takeIf { id.contains(":") }, options.optString("appName").takeIf { it.isNotBlank() }, rename = false)
    if (!state.getBoolean("enabled")) return
    val builder = NotificationCompat.Builder(context, state.getString("channelId"))
      .setSmallIcon(R.drawable.ic_shell_notify)
      .setContentTitle(title.ifBlank { "生花" })
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
    if (id.contains(":")) builder.setContentIntent(openIntent(context, id, "open", options))
    val actions = options.optJSONArray("actions") ?: JSONArray()
    for (i in 0 until actions.length()) {
      val action = actions.getJSONObject(i)
      val press = if (action.optString("behavior", "open") == "open") openIntent(context, id, action.getString("id"), options)
      else {
        val intent = Intent(context, ShellAlarmReceiver::class.java).setAction(ACTION_BUTTON)
          .setData(Uri.parse("zijinhua-notification://action/${Uri.encode(id)}/${Uri.encode(action.getString("id"))}"))
          .putExtra("entry", JSONObject().put("id", id).put("title", title).put("body", body).put("options", options).toString())
          .putExtra("action", action.toString())
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      }
      builder.addAction(0, action.getString("title"), press)
    }
    NotificationManagerCompat.from(context).notify(id, 0, builder.build())
    Log.i(TAG, "notification posted id=$id")
  }

  private fun openIntent(context: Context, id: String, action: String, options: JSONObject): PendingIntent {
    val appId = id.substringBefore(":")
    val token = UUID.randomUUID().toString()
    val url = Uri.Builder().scheme("zijinhua").authority("applications").appendPath(appId)
      .appendQueryParameter("notification", "$id:$action").apply { options.optJSONObject("params")?.let { appendQueryParameter("p", it.toString()) } }.build()
    val event = JSONObject().put("token", token).put("appId", appId).put("id", options.optString("sourceId", id.substringAfter(":")))
      .put("action", action).put("params", options.optJSONObject("params") ?: JSONObject())
    val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(url)
      .putExtra("notificationEvent", event.toString()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private val captured = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Long>>()
  private val eventIo = java.util.concurrent.Executors.newSingleThreadExecutor()
  fun capture(context: Context, intent: Intent?) {
    val raw = intent?.getStringExtra("notificationEvent") ?: return
    intent.removeExtra("notificationEvent")
    if (raw.length > 8192) return
    captured.add(raw to System.currentTimeMillis())
    val app = context.applicationContext
    eventIo.execute { runCatching { synchronized(this) { drainCaptured(app) } }.onFailure { Log.w(TAG, "notification event persistence deferred") } }
  }
  // RN reads pending clicks even if it subscribes before the disk write finishes.
  private fun drainCaptured(context: Context) {
    while (true) {
      val (raw, atMs) = captured.poll() ?: return
      try { persistCaptured(context, raw, atMs) }
      catch (error: Exception) { captured.add(raw to atMs); throw error }
    }
  }
  private fun persistCaptured(context: Context, raw: String, atMs: Long) {
    val event = try {
      JSONObject(raw).apply {
        require(optString("token").matches(Regex("[a-f0-9-]{36}")))
        require(optString("appId").matches(Regex("[a-z][a-z0-9-]{0,63}")))
        require(optString("id").matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(optString("action").matches(Regex("[A-Za-z0-9._-]{1,40}")))
        require((optJSONObject("params")?.toString()?.toByteArray()?.size ?: 0) <= 4096)
        put("atMs", atMs)
      }
    } catch (_: Exception) { return }
    val entries = events(context).filter { it.optString("token") != event.getString("token") }.takeLast(63) + event
    saveEvents(context, entries)
    NotificationManagerCompat.from(context).cancel("${event.getString("appId")}:${event.getString("id")}", 0)
  }

  private fun events(context: Context): List<JSONObject> {
    val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("events", "[]"))
    return (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optLong("atMs") > System.currentTimeMillis() - 7 * 86400000L }
  }
  private fun saveEvents(context: Context, entries: List<JSONObject>) {
    check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("events", JSONArray(entries).toString()).commit())
  }

  @Synchronized
  fun notificationEvents(context: Context, appId: String, tokens: JSONArray?): JSONObject {
    drainCaptured(context)
    val ack = tokens?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() } ?: emptySet()
    val entries = events(context).filterNot { it.optString("appId") == appId && it.optString("token") in ack }
    if (ack.isNotEmpty()) saveEvents(context, entries)
    return JSONObject().put("ok", true).put("items", JSONArray(entries.filter { it.optString("appId") == appId }))
  }

  @Synchronized
  fun button(context: Context, intent: Intent) {
    val entry = JSONObject(intent.getStringExtra("entry") ?: return)
    val action = JSONObject(intent.getStringExtra("action") ?: return)
    val id = entry.getString("id")
    val event = JSONObject().put("token", UUID.randomUUID().toString()).put("appId", id.substringBefore(":"))
      .put("id", entry.getJSONObject("options").optString("sourceId", id.substringAfter(":")))
      .put("action", action.getString("id")).put("params", entry.getJSONObject("options").optJSONObject("params") ?: JSONObject())
      .put("atMs", System.currentTimeMillis())
    if (action.getString("behavior") == "complete") cancel(context, entry.getJSONObject("options").optString("parentId", id))
    else {
      val options = JSONObject(entry.getJSONObject("options").toString()).apply { remove("repeat"); remove("appName") }
      val parent = options.optString("parentId", id)
      options.put("parentId", parent).put("sourceId", parent.substringAfter(":"))
      val snoozeId = parent.substringBefore(":") + ":" + UUID.randomUUID().toString().replace("-", "").take(14)
      schedule(context, snoozeId, entry.optString("title"), entry.optString("body"), System.currentTimeMillis() + action.optLong("snoozeMs", 600000), options)
      NotificationManagerCompat.from(context).cancel(id, 0)
    }
    saveEvents(context, events(context).takeLast(63) + event)
  }

  @Synchronized
  fun fire(context: Context, intent: Intent) {
    val id = intent.getStringExtra(EXTRA_ID) ?: return
    val entry = read(context).firstOrNull { it.getString("id") == id } ?: return
    val options = entry.optJSONObject("options") ?: JSONObject()
    val left = read(context).filter { it.getString("id") != id }.toMutableList()
    options.optJSONObject("repeat")?.let {
      val next = nextTime(it, System.currentTimeMillis())
      entry.put("atMs", next); arm(context, id, entry.optString("title"), entry.optString("body"), next); left.add(entry)
    }
    write(context, left)
    show(context, id, entry.optString("title"), entry.optString("body"), options)
  }

  @Synchronized
  fun reschedule(context: Context) {
    val now = System.currentTimeMillis()
    val future = mutableListOf<JSONObject>()
    for (item in read(context)) {
      val options = item.optJSONObject("options") ?: JSONObject()
      val atMs = options.optJSONObject("repeat")?.let { nextTime(it, now) } ?: item.getLong("atMs")
      if (atMs <= now) show(context, item.getString("id"), item.optString("title"), item.optString("body"), options)
      else { item.put("atMs", atMs); arm(context, item.getString("id"), "", "", atMs); future.add(item) }
    }
    write(context, future)
  }

  private fun arm(context: Context, id: String, title: String, body: String, trigger: Long): Boolean {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
      ?: throw IllegalStateException("AlarmManager unavailable")
    val previous = PendingIntent.getBroadcast(context, notificationId(id), Intent(context, ShellAlarmReceiver::class.java).setAction(ACTION_FIRE), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    if (previous != null) { alarmManager.cancel(previous); previous.cancel() }
    val pending = pending(context, id, title, body)
    if (canExact(alarmManager)) {
      try {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        return true
      } catch (_: SecurityException) { /* Permission may be revoked between checking and arming. */ }
    }
    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    return false
  }

  private fun pending(context: Context, id: String, title: String, body: String): PendingIntent {
    val intent = Intent(context, ShellAlarmReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("zijinhua-notification://alarm/${Uri.encode(id)}"))
    intent.putExtra(EXTRA_ID, id)
    intent.putExtra(EXTRA_TITLE, title)
    intent.putExtra(EXTRA_BODY, body)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(context, notificationId(id), intent, flags)
  }

  private fun persist(context: Context, id: String, title: String, body: String, atMs: Long, options: JSONObject) {
    val next = read(context).filter { it.getString("id") != id }.toMutableList()
    next.add(JSONObject().put("id", id).put("title", title).put("body", body).put("atMs", atMs).put("options", options))
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
    check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).commit()) { "cannot persist reminder" }
  }

  fun ensureChannel(context: Context, appId: String? = null, appName: String? = null, rename: Boolean = true): String {
    if (appId != null) require(appId.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "invalid application id" }
    val id = if (appId == null) CHANNEL_ID else "zijinhua.application.$appId"
    if (Build.VERSION.SDK_INT < 26) return id
    val manager = context.getSystemService(NotificationManager::class.java) ?: return id
    val existing = manager.getNotificationChannel(id)
    val name = appName?.trim()?.take(100)?.takeIf { it.isNotEmpty() }
      ?: existing?.name?.toString() ?: appId ?: "提醒"
    // Rename in place. Never delete/recreate or overwrite the user's sound,
    // vibration, importance, or disabled state when an application is updated.
    if (existing == null) {
      val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
      channel.description = "应用定时提醒"
      manager.createNotificationChannel(channel)
    } else if (rename && existing.name.toString() != name) {
      existing.name = name
      manager.createNotificationChannel(existing)
    }
    return id
  }

  private fun canExact(alarmManager: AlarmManager?): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return alarmManager?.canScheduleExactAlarms() == true
  }

  fun notificationId(id: String): Int = id.hashCode() and 0x7fffffff

  fun requireId(id: String): String {
    if (!id.matches(Regex("[A-Za-z0-9._:-]{1,80}"))) {
      throw IllegalArgumentException("bad id")
    }
    return id
  }
}
