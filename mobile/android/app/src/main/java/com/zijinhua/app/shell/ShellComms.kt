package com.zijinhua.app.shell

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.provider.CallLog
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.zijinhua.app.plugin.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ShellComms {
  private const val TAG = "ShellComms"
  private var recorder: MediaRecorder? = null
  private var recording: File? = null
  private var startedAt = 0L

  fun register(registry: PluginRegistry) {
    registry.register("shell", "recordStart") { _ -> recordStart().toString() }
    registry.register("shell", "recordStop") { _ -> recordStop().toString() }
    registry.register("shell", "smsList") { payload -> smsList(JSONObject(payload.ifBlank { "{}" })).toString() }
    registry.register("shell", "smsSend") { payload -> smsSend(JSONObject(payload)).toString() }
    registry.register("shell", "callLog") { payload -> callLog(JSONObject(payload.ifBlank { "{}" })).toString() }
  }

  private fun recordStart(): JSONObject {
    val context = ShellCapabilities.hostContext()
    if (!granted(context, Manifest.permission.RECORD_AUDIO)) {
      return JSONObject().put("ok", false).put("error", "permission")
    }
    if (recorder != null) return JSONObject().put("ok", false).put("error", "busy")
    val dir = File(context.filesDir, "user/recordings")
    dir.mkdirs()
    val file = File(dir, "rec-${System.currentTimeMillis()}.m4a")
    val created = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else {
      @Suppress("DEPRECATION")
      MediaRecorder()
    }
    try {
      created.setAudioSource(MediaRecorder.AudioSource.MIC)
      created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      created.setAudioSamplingRate(44100)
      created.setAudioEncodingBitRate(96000)
      created.setOutputFile(file.absolutePath)
      created.prepare()
      created.start()
    } catch (error: Exception) {
      created.release()
      file.delete()
      Log.i(TAG, "record start failed ${error.message}")
      return JSONObject().put("ok", false).put("error", "recorder")
    }
    recorder = created
    recording = file
    startedAt = System.currentTimeMillis()
    Log.i(TAG, "record started")
    return JSONObject().put("ok", true).put("path", file.absolutePath)
  }

  private fun recordStop(): JSONObject {
    val created = recorder ?: return JSONObject().put("ok", false).put("error", "idle")
    val file = recording
    recorder = null
    recording = null
    try {
      created.stop()
    } catch (error: Exception) {
      Log.i(TAG, "record stop ${error.message}")
    } finally {
      created.release()
    }
    val ms = System.currentTimeMillis() - startedAt
    Log.i(TAG, "record stopped ms=$ms bytes=${file?.length() ?: 0}")
    return JSONObject()
      .put("ok", true)
      .put("path", file?.absolutePath ?: "")
      .put("bytes", file?.length() ?: 0L)
      .put("ms", ms)
  }

  private fun smsList(input: JSONObject): JSONObject {
    val context = ShellCapabilities.hostContext()
    if (!granted(context, Manifest.permission.READ_SMS)) {
      return JSONObject().put("ok", false).put("error", "permission")
    }
    val limit = input.optInt("limit", 30).coerceIn(1, 50)
    val uri = if (input.optString("box") == "sent") Telephony.Sms.Sent.CONTENT_URI else Telephony.Sms.Inbox.CONTENT_URI
    val items = JSONArray()
    context.contentResolver.query(
      uri,
      arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
      null,
      null,
      "${Telephony.Sms.DATE} DESC",
    )?.use { cursor ->
      val address = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
      val body = cursor.getColumnIndex(Telephony.Sms.BODY)
      val date = cursor.getColumnIndex(Telephony.Sms.DATE)
      while (cursor.moveToNext() && items.length() < limit) {
        items.put(
          JSONObject()
            .put("address", if (address >= 0) cursor.getString(address) ?: "" else "")
            .put("body", if (body >= 0) cursor.getString(body) ?: "" else "")
            .put("dateMs", if (date >= 0) cursor.getLong(date) else 0L),
        )
      }
    }
    Log.i(TAG, "sms list count=${items.length()}")
    return JSONObject().put("ok", true).put("items", items)
  }

  private fun smsSend(input: JSONObject): JSONObject {
    val context = ShellCapabilities.hostContext()
    if (!granted(context, Manifest.permission.SEND_SMS)) {
      return JSONObject().put("ok", false).put("error", "permission")
    }
    val to = input.optString("to").trim()
    val body = input.optString("body")
    if (!to.matches(Regex("^[+0-9][0-9 \\-]{2,22}$"))) {
      return JSONObject().put("ok", false).put("error", "number")
    }
    if (body.isBlank() || body.length > 2000) {
      return JSONObject().put("ok", false).put("error", "body")
    }
    val sms = if (Build.VERSION.SDK_INT >= 31) {
      context.getSystemService(SmsManager::class.java)
    } else {
      @Suppress("DEPRECATION")
      SmsManager.getDefault()
    } ?: return JSONObject().put("ok", false).put("error", "sms")
    val parts = sms.divideMessage(body)
    if (parts.size == 1) sms.sendTextMessage(to, null, body, null, null)
    else sms.sendMultipartTextMessage(to, null, parts, null, null)
    Log.i(TAG, "sms sent parts=${parts.size}")
    return JSONObject().put("ok", true).put("parts", parts.size)
  }

  private fun callLog(input: JSONObject): JSONObject {
    val context = ShellCapabilities.hostContext()
    if (!granted(context, Manifest.permission.READ_CALL_LOG)) {
      return JSONObject().put("ok", false).put("error", "permission")
    }
    val limit = input.optInt("limit", 30).coerceIn(1, 50)
    val items = JSONArray()
    context.contentResolver.query(
      CallLog.Calls.CONTENT_URI,
      arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
      null,
      null,
      "${CallLog.Calls.DATE} DESC",
    )?.use { cursor ->
      val number = cursor.getColumnIndex(CallLog.Calls.NUMBER)
      val name = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
      val type = cursor.getColumnIndex(CallLog.Calls.TYPE)
      val date = cursor.getColumnIndex(CallLog.Calls.DATE)
      val duration = cursor.getColumnIndex(CallLog.Calls.DURATION)
      while (cursor.moveToNext() && items.length() < limit) {
        items.put(
          JSONObject()
            .put("number", if (number >= 0) cursor.getString(number) ?: "" else "")
            .put("name", if (name >= 0) cursor.getString(name) ?: "" else "")
            .put("type", callType(if (type >= 0) cursor.getInt(type) else 0))
            .put("dateMs", if (date >= 0) cursor.getLong(date) else 0L)
            .put("durationSec", if (duration >= 0) cursor.getLong(duration) else 0L),
        )
      }
    }
    Log.i(TAG, "call log count=${items.length()}")
    return JSONObject().put("ok", true).put("items", items)
  }

  private fun callType(type: Int): String {
    return when (type) {
      CallLog.Calls.INCOMING_TYPE -> "incoming"
      CallLog.Calls.OUTGOING_TYPE -> "outgoing"
      CallLog.Calls.MISSED_TYPE -> "missed"
      CallLog.Calls.REJECTED_TYPE -> "rejected"
      CallLog.Calls.BLOCKED_TYPE -> "blocked"
      else -> "other"
    }
  }

  private fun granted(context: android.content.Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
  }
}
