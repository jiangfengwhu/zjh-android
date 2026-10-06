package com.zijinhua.app.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.zijinhua.app.plugin.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ShellPhone {
  private const val TAG = "ShellPhone"

  fun register(registry: PluginRegistry) {
    registry.register("shell", "device") { _ -> device().toString() }
    registry.register("shell", "clipboardGet") { _ -> clipboardGet().toString() }
    registry.register("shell", "clipboardSet") { payload -> clipboardSet(JSONObject(payload)).toString() }
    registry.register("shell", "contacts") { payload -> contacts(JSONObject(payload.ifBlank { "{}" })).toString() }
    registry.register("shell", "calendarEvents") { payload -> calendarEvents(JSONObject(payload)).toString() }
    registry.register("shell", "calendarInsert") { payload -> calendarInsert(JSONObject(payload)).toString() }
    registry.register("shell", "openUrl") { payload -> openUrl(JSONObject(payload)).toString() }
    registry.register("shell", "motion") { _ -> motion().toString() }
    registry.register("shell", "torch") { payload -> torch(JSONObject(payload)).toString() }
    registry.register("shell", "keepAwake") { payload -> keepAwake(JSONObject(payload)).toString() }
    registry.register("shell", "speak") { payload -> speak(JSONObject(payload)).toString() }
  }

  private var speech: TextToSpeech? = null

  private fun context(): Context {
    return ShellCapabilities.hostContext()
  }

  private fun device(): JSONObject {
    val context = context()
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return JSONObject()
      .put("ok", true)
      .put("brand", Build.BRAND)
      .put("model", Build.MODEL)
      .put("sdk", Build.VERSION.SDK_INT)
      .put("release", Build.VERSION.RELEASE)
      .put("battery", percent)
      .put("charging", charging)
  }

  private fun clipboardGet(): JSONObject {
    val manager = context().getSystemService(ClipboardManager::class.java)
    val text = manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context())?.toString() ?: ""
    return JSONObject().put("ok", true).put("text", text)
  }

  private fun clipboardSet(input: JSONObject): JSONObject {
    val manager = context().getSystemService(ClipboardManager::class.java)
      ?: return JSONObject().put("ok", false).put("error", "clipboard")
    manager.setPrimaryClip(ClipData.newPlainText("zijinhua", input.optString("text")))
    return JSONObject().put("ok", true)
  }

  private fun contacts(input: JSONObject): JSONObject {
    val context = context()
    if (!granted(context, android.Manifest.permission.READ_CONTACTS)) {
      return JSONObject().put("ok", false).put("error", "permission")
    }
    val limit = input.optInt("limit", 40).coerceIn(1, 80)
    val query = input.optString("query").trim()
    val selection = if (query.isEmpty()) null else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
    val args = if (query.isEmpty()) null else arrayOf("%$query%")
    val items = JSONArray()
    context.contentResolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
      ),
      selection,
      args,
      "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
    )?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
      val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
      while (cursor.moveToNext() && items.length() < limit) {
        items.put(
          JSONObject()
            .put("name", if (nameIndex >= 0) cursor.getString(nameIndex) ?: "" else "")
            .put("number", if (numberIndex >= 0) cursor.getString(numberIndex) ?: "" else ""),
        )
      }
    }
    Log.i(TAG, "contacts count=${items.length()}")
    return JSONObject().put("ok", true).put("items", items)
  }

  private fun calendarEvents(input: JSONObject): JSONObject {
    val context = context()
    if (!granted(context, android.Manifest.permission.READ_CALENDAR)) {
      return JSONObject().put("ok", false).put("error", "permission")
    }
    val fromMs = input.getLong("fromMs")
    val toMs = input.getLong("toMs")
    val items = JSONArray()
    context.contentResolver.query(
      CalendarContract.Events.CONTENT_URI,
      arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.TITLE,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND,
      ),
      "${CalendarContract.Events.DTSTART}>=? AND ${CalendarContract.Events.DTSTART}<=?",
      arrayOf(fromMs.toString(), toMs.toString()),
      "${CalendarContract.Events.DTSTART} ASC",
    )?.use { cursor ->
      val idIndex = cursor.getColumnIndex(CalendarContract.Events._ID)
      val titleIndex = cursor.getColumnIndex(CalendarContract.Events.TITLE)
      val startIndex = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
      val endIndex = cursor.getColumnIndex(CalendarContract.Events.DTEND)
      while (cursor.moveToNext() && items.length() < 80) {
        items.put(
          JSONObject()
            .put("id", if (idIndex >= 0) cursor.getString(idIndex) ?: "" else "")
            .put("title", if (titleIndex >= 0) cursor.getString(titleIndex) ?: "" else "")
            .put("startMs", if (startIndex >= 0) cursor.getLong(startIndex) else 0L)
            .put("endMs", if (endIndex >= 0) cursor.getLong(endIndex) else 0L),
        )
      }
    }
    Log.i(TAG, "calendar events count=${items.length()}")
    return JSONObject().put("ok", true).put("items", items)
  }

  private fun calendarInsert(input: JSONObject): JSONObject {
    val context = context()
    if (!granted(context, android.Manifest.permission.WRITE_CALENDAR)) {
      return JSONObject().put("ok", false).put("error", "permission")
    }
    val calendarId = writableCalendarId(context)
      ?: return JSONObject().put("ok", false).put("error", "no calendar")
    val values = android.content.ContentValues().apply {
      put(CalendarContract.Events.CALENDAR_ID, calendarId)
      put(CalendarContract.Events.TITLE, input.optString("title"))
      put(CalendarContract.Events.DESCRIPTION, input.optString("description"))
      put(CalendarContract.Events.DTSTART, input.getLong("startMs"))
      put(CalendarContract.Events.DTEND, input.getLong("endMs"))
      put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
    }
    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
      ?: return JSONObject().put("ok", false).put("error", "insert")
    Log.i(TAG, "calendar inserted")
    return JSONObject().put("ok", true).put("id", uri.lastPathSegment ?: "")
  }

  private fun writableCalendarId(context: Context): Long? {
    context.contentResolver.query(
      CalendarContract.Calendars.CONTENT_URI,
      arrayOf(CalendarContract.Calendars._ID),
      "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
      arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
      null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    return null
  }

  private fun openUrl(input: JSONObject): JSONObject {
    val host = ShellCapabilities.hostActivity() ?: return JSONObject().put("ok", false).put("error", "activity")
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(input.getString("url")))
    host.runOnUiThread { host.startActivity(intent) }
    return JSONObject().put("ok", true)
  }

  private fun granted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
  }

  private fun motion(): JSONObject {
    val manager = context().getSystemService(SensorManager::class.java)
      ?: return JSONObject().put("ok", false).put("error", "sensor")
    val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
      ?: return JSONObject().put("ok", false).put("error", "sensor")
    val latch = CountDownLatch(1)
    val sample = FloatArray(3)
    val listener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        event.values.copyInto(sample)
        latch.countDown()
        manager.unregisterListener(this)
      }

      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    if (!latch.await(2, TimeUnit.SECONDS)) {
      manager.unregisterListener(listener)
      return JSONObject().put("ok", false).put("error", "timeout")
    }
    return JSONObject().put("ok", true).put("x", sample[0].toDouble()).put("y", sample[1].toDouble()).put("z", sample[2].toDouble())
  }

  private fun torch(input: JSONObject): JSONObject {
    val context = context()
    if (!granted(context, android.Manifest.permission.CAMERA)) {
      return JSONObject().put("ok", false).put("error", "permission")
    }
    val cameras = context.getSystemService(CameraManager::class.java)
      ?: return JSONObject().put("ok", false).put("error", "camera")
    val id = cameras.cameraIdList.firstOrNull { cameraId ->
      cameras.getCameraCharacteristics(cameraId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    } ?: return JSONObject().put("ok", false).put("error", "torch")
    cameras.setTorchMode(id, input.optBoolean("on"))
    return JSONObject().put("ok", true).put("on", input.optBoolean("on"))
  }

  private fun keepAwake(input: JSONObject): JSONObject {
    val host = ShellCapabilities.hostActivity() ?: return JSONObject().put("ok", false).put("error", "activity")
    val on = input.optBoolean("on", true)
    host.runOnUiThread {
      if (on) host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      else host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    return JSONObject().put("ok", true).put("on", on)
  }

  private fun speak(input: JSONObject): JSONObject {
    val text = input.optString("text")
    if (text.isBlank()) return JSONObject().put("ok", false).put("error", "text")
    val engine = speechEngine() ?: return JSONObject().put("ok", false).put("error", "speech")
    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zijinhua")
    Log.i(TAG, "speak chars=${text.length}")
    return JSONObject().put("ok", true)
  }

  private fun speechEngine(): TextToSpeech? {
    speech?.let { return it }
    val latch = CountDownLatch(1)
    val created = TextToSpeech(context()) { latch.countDown() }
    speech = created
    latch.await(5, TimeUnit.SECONDS)
    created.language = Locale.CHINA
    return created
  }

}
