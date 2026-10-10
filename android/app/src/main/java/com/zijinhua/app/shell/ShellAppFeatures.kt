package com.zijinhua.app.shell

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.zijinhua.app.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Interactive work runs on the main looper; media copying stays on the bridge IO pool. */
object ShellAppFeatures {
  val methods = setOf("shortcutStatus", "shortcutAdd", "shortcutRemove", "shortcutList", "appKeepAwake", "saveToGallery", "calendarCompose",
    "speechStatus", "speechSpeak", "speechStop", "speechRecognize", "speechFinish", "speechCancelAll", "speechCancel",
    "sensorStatus", "sensorStart", "sensorStop", "biometricStatus", "biometricAuthenticate", "biometricCancel", "appNativeStop", "removeApplicationShortcuts")
  private val main = Handler(Looper.getMainLooper())
  private data class Task(val appId: String, val id: String, val reply: (JSONObject) -> Unit, var timeout: Runnable? = null, var replied: Boolean = false)
  private var applicationContext: Context? = null
  fun attachContext(context: Context) { applicationContext = context.applicationContext }
  private var speechGeneration = 0
  private var tts: TextToSpeech? = null
  private var ttsReady = false
  private var speaking: Task? = null
  private var recognizer: SpeechRecognizer? = null
  private var recognizing: Task? = null
  private var biometric: Task? = null
  private var biometricCancel: CancellationSignal? = null
  private val awake = mutableSetOf<String>()
  private fun updateAwake() { ShellCapabilities.hostActivity()?.window?.let { window -> if (awake.isEmpty()) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
  private val sensors = mutableMapOf<String, Pair<String, SensorEventListener>>()
  private fun context() = applicationContext ?: ShellCapabilities.hostContext()
  private fun resolve(task: Task, value: JSONObject) { if (!task.replied) { task.replied = true; task.reply(value) } }
  private fun complete(task: Task, value: JSONObject) { task.timeout?.let { main.removeCallbacks(it) }; task.timeout = null; resolve(task, value) }
  private fun result(error: String? = null) = JSONObject().put("ok", error == null).apply { if (error != null) put("error", error) }
  private fun app(input: JSONObject): String = input.getString("appId").also { require(it.matches(Regex("[a-z][a-z0-9-]{0,63}"))) }
  private fun id(input: JSONObject) = input.getString("requestId").also { require(it.matches(Regex("[A-Za-z0-9._:-]{1,160}"))) }

  fun call(method: String, input: JSONObject, emit: (JSONObject) -> Unit, reply: (JSONObject) -> Unit) {
    if (method == "saveToGallery") {
      try { reply(saveToGallery(input)) } catch (_: Exception) { reply(result("file")) }
      return
    }
    val shortcutIcon = if (method == "shortcutAdd") {
      try { shortcutIcon(input) } catch (_: Exception) { reply(result("file")); return }
    } else null
    main.post {
      try {
        val appId = app(input)
        when (method) {
          "shortcutStatus" -> reply(result().put("supported", context().getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true))
          "shortcutList" -> reply(result().put("items", JSONArray(context().getSystemService(ShortcutManager::class.java)?.pinnedShortcuts?.filter { it.id.startsWith("$appId:") }?.map { JSONObject().put("id", it.id.substringAfter(':')).put("enabled", it.isEnabled).put("label", it.shortLabel) } ?: emptyList<JSONObject>())))
          "appKeepAwake" -> { if (input.optBoolean("on")) awake.add(appId) else awake.remove(appId); updateAwake(); reply(result()) }
          "shortcutAdd" -> reply(shortcut(input, appId, requireNotNull(shortcutIcon)))
          "shortcutRemove" -> { removeShortcut(appId, input.getString("id")); reply(result()) }
          "removeApplicationShortcuts" -> { removeShortcuts(appId); reply(result()) }
          "calendarCompose" -> reply(calendar(input))
          "speechStatus" -> reply(result().put("recognition", SpeechRecognizer.isRecognitionAvailable(context()))
            .put("onDevice", Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context()))
            .put("microphone", ContextCompat.checkSelfPermission(context(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .put("tts", context().packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()))
          "speechSpeak" -> speak(input, appId, reply)
          "speechStop" -> { if (!input.has("requestId") || speaking?.id == id(input)) stopSpeech(appId); reply(result()) }
          "speechRecognize" -> recognize(input, appId, emit, reply)
          "speechFinish" -> { if (recognizing?.appId == appId) recognizer?.stopListening(); reply(result()) }
          "speechCancelAll" -> { if (recognizing?.appId == appId) stopRecognition(); reply(result()) }
          "speechCancel" -> { if (recognizing?.appId == appId && recognizing?.id == id(input)) stopRecognition(); reply(result()) }
          "sensorStatus" -> { val manager = context().getSystemService(SensorManager::class.java); reply(result().put("items", JSONArray(sensorTypes.keys.filter { manager?.getDefaultSensor(sensorTypes.getValue(it)) != null }))) }
          "sensorStart" -> reply(startSensor(input, appId, emit))
          "sensorStop" -> { stopSensor("$appId:${id(input)}"); reply(result()) }
          "biometricStatus" -> reply(biometricStatus())
          "biometricAuthenticate" -> authenticate(input, appId, reply)
          "biometricCancel" -> { if (biometric?.appId == appId && biometric?.id == id(input)) stopBiometric(); reply(result()) }
          "appNativeStop" -> { stopApp(appId, input.optBoolean("includeBiometric", true)); reply(result()) }
          else -> reply(result("unsupported"))
        }
      } catch (_: Exception) {
        val requestId = input.optString("requestId")
        when {
          speaking?.id == requestId -> stopSpeech(error = "unavailable")
          recognizing?.id == requestId -> stopRecognition("unavailable")
          biometric?.id == requestId -> stopBiometric("unavailable")
          else -> reply(result("unavailable"))
        }
      }
    }
  }

  private fun ownedFile(input: JSONObject): File {
    val appId = app(input)
    val file = File(input.getString("path")).canonicalFile
    val root = File(context().filesDir, "applications/$appId").canonicalFile
    require(file.path.startsWith(root.path + File.separator) && file.isFile)
    return file
  }

  private fun saveToGallery(input: JSONObject): JSONObject {
    if (Build.VERSION.SDK_INT < 29) return result("unsupported")
    val file = ownedFile(input)
    val mime = input.getString("mime")
    require(mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif", "video/mp4", "video/webm"))
    val name = input.optString("name").ifBlank { file.name }
    require(name.length <= 120 && !name.contains('/') && !name.contains('\\'))
    val video = mime.startsWith("video/")
    val resolver = context().contentResolver
    val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, mime)
      put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/生花")
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(collection, values) ?: return result("file")
    try {
      resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("output")
      require(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
      return result()
    } catch (error: Exception) { resolver.delete(uri, null, null); throw error }
  }

  private fun shortcut(input: JSONObject, appId: String, icon: Icon): JSONObject {
    val manager = context().getSystemService(ShortcutManager::class.java) ?: return result("unsupported")
    if (!manager.isRequestPinShortcutSupported) return result("unsupported")
    val key = shortcutId(appId, input.getString("id"))
    val label = input.getString("label").trim().also { require(it.isNotEmpty() && it.length <= 40) }
    val params = input.optJSONObject("params")
    require((params?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= 4096)
    val url = Uri.Builder().scheme("zijinhua").authority("applications").appendPath(appId)
      .apply { params?.let { appendQueryParameter("p", it.toString()) } }.build()
    val intent = Intent(context(), MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(url)
    val shortcut = ShortcutInfo.Builder(context(), key).setShortLabel(label).setIcon(icon).setIntent(intent).build()
    val pinned = manager.pinnedShortcuts.any { it.id == key }
    if (pinned) { manager.enableShortcuts(listOf(key)); return result().put("requested", manager.updateShortcuts(listOf(shortcut))).put("existing", true) }
    return result().put("requested", manager.requestPinShortcut(shortcut, null)).put("existing", false)
  }
  private fun shortcutIcon(input: JSONObject): Icon {
    return if (input.has("path")) {
      val file = ownedFile(input)
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.path, bounds)
      require(bounds.outWidth > 0 && bounds.outHeight > 0 && file.length() <= 6 * 1024 * 1024)
      val options = BitmapFactory.Options().apply { while (bounds.outWidth / inSampleSize > 256 || bounds.outHeight / inSampleSize > 256) inSampleSize *= 2 }
      Icon.createWithBitmap(requireNotNull(BitmapFactory.decodeFile(file.path, options)))
    } else {
      val bitmap = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
      val canvas = android.graphics.Canvas(bitmap)
      val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
      paint.color = android.graphics.Color.parseColor(input.optString("accent", "#E65088"))
      canvas.drawCircle(96f, 96f, 96f, paint)
      paint.color = android.graphics.Color.WHITE; paint.textSize = 88f; paint.textAlign = android.graphics.Paint.Align.CENTER
      val label = input.getString("label").trim()
      val initial = if (label.isNotEmpty()) label.substring(0, label.offsetByCodePoints(0, 1)) else ""
      canvas.drawText(initial, 96f, 96f - (paint.ascent() + paint.descent()) / 2, paint)
      Icon.createWithBitmap(bitmap)
    }
  }
  private fun shortcutId(appId: String, value: String) = "$appId:$value".also { require(value.matches(Regex("[A-Za-z0-9._-]{1,60}"))) }
  private fun removeShortcut(appId: String, value: String) {
    val key = shortcutId(appId, value)
    context().getSystemService(ShortcutManager::class.java)?.apply { disableShortcuts(listOf(key), "应用入口已移除"); removeDynamicShortcuts(listOf(key)) }
  }
  fun removeShortcuts(appId: String) {
    context().getSystemService(ShortcutManager::class.java)?.apply {
      val ids = pinnedShortcuts.filter { it.id.startsWith("$appId:") }.map { it.id }
      if (ids.isNotEmpty()) { disableShortcuts(ids, "应用已卸载"); removeDynamicShortcuts(ids) }
    }
  }

  private fun calendar(input: JSONObject): JSONObject {
    val activity = ShellCapabilities.hostActivity() ?: return result("unavailable")
    val title = input.getString("title").trim().also { require(it.isNotEmpty() && it.length <= 200) }
    val start = input.getLong("startMs"); val end = input.getLong("endMs"); require(start > 0 && end > start)
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
      .putExtra(CalendarContract.Events.TITLE, title).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
      .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end).putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, input.optBoolean("allDay"))
      .putExtra(CalendarContract.Events.DESCRIPTION, input.optString("description").take(2000))
      .putExtra(CalendarContract.Events.EVENT_LOCATION, input.optString("location").take(300))
    activity.startActivity(intent)
    return result() // System calendars do not consistently report whether the user saved.
  }

  private fun speak(input: JSONObject, appId: String, reply: (JSONObject) -> Unit) {
    val text = input.getString("text").also { require(it.isNotBlank() && it.length <= TextToSpeech.getMaxSpeechInputLength()) }
    val rate = input.optDouble("rate", 1.0); val pitch = input.optDouble("pitch", 1.0)
    require(rate.isFinite() && rate in 0.25..2.0 && pitch.isFinite() && pitch in 0.5..2.0)
    val language = input.optString("language", "zh-CN").also { require(it.length <= 40) }
    if (speaking != null) {
      if (!input.optBoolean("waitForCompletion", true) && speaking?.appId == appId) stopSpeech(appId)
      else { reply(result("busy")); return }
    }
    val task = Task(appId, id(input), reply); speaking = task
    val timeout = Runnable { if (speaking === task) stopSpeech(appId, "timeout") }
    task.timeout = timeout
    main.postDelayed(timeout, 180_000)
    fun start() {
      if (speaking !== task) return
      val engine = tts ?: return
      if (engine.setLanguage(Locale.forLanguageTag(language)) < 0) { stopSpeech(appId, "language"); return }
      engine.setSpeechRate(rate.toFloat()); engine.setPitch(pitch.toFloat())
      engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) { main.post { if (speaking === task && utteranceId == task.id) { speaking = null; complete(task, result()) } } }
        @Deprecated("Platform callback") override fun onError(utteranceId: String?) { main.post { if (speaking === task && utteranceId == task.id) stopSpeech(appId, "speech") } }
      })
      if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, task.id) == TextToSpeech.ERROR) stopSpeech(appId, "speech")
      else if (!input.optBoolean("waitForCompletion", true)) resolve(task, result())
    }
    if (ttsReady) start() else {
      tts?.shutdown()
      val generation = ++speechGeneration
      tts = TextToSpeech(context()) { status -> main.post {
        if (generation != speechGeneration) return@post
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) start() else { tts?.shutdown(); tts = null; if (speaking === task) stopSpeech(appId, "unavailable") }
      } }
      main.postDelayed({ if (generation == speechGeneration && !ttsReady && speaking === task) { speechGeneration++; tts?.shutdown(); tts = null; stopSpeech(appId, "timeout") } }, 8000)
    }
  }
  private fun stopSpeech(appId: String? = null, error: String = "cancel") {
    val task = speaking ?: return
    if (appId != null && task.appId != appId) return
    speaking = null; tts?.stop(); complete(task, result(error))
  }

  private fun recognize(input: JSONObject, appId: String, emit: (JSONObject) -> Unit, reply: (JSONObject) -> Unit) {
    if (recognizing != null) { reply(result("busy")); return }
    if (ContextCompat.checkSelfPermission(context(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { reply(result("permission")); return }
    val onDevice = input.optBoolean("onDevice")
    if (onDevice && (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context())) || !onDevice && !SpeechRecognizer.isRecognitionAvailable(context())) { reply(result("unsupported")); return }
    val language = input.optString("language", "zh-CN").also { require(it.length <= 40) }
    val task = Task(appId, id(input), reply)
    val engine = if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context()) else SpeechRecognizer.createSpeechRecognizer(context())
    recognizing = task; recognizer = engine
    val timeout = Runnable { if (recognizing === task) stopRecognition("timeout") }
    task.timeout = timeout
    main.postDelayed(timeout, 60_000)
    fun finish(value: JSONObject) {
      if (recognizing !== task) return
      recognizing = null; recognizer = null; runCatching { engine.destroy() }; complete(task, value)
    }
    engine.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
      override fun onBeginningOfSpeech() = Unit
      override fun onRmsChanged(rmsdB: Float) = Unit
      override fun onBufferReceived(buffer: ByteArray?) = Unit
      override fun onEndOfSpeech() = Unit
      override fun onError(error: Int) { finish(result(when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_NO_MATCH -> "no-match"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        else -> "speech"
      })) }
      override fun onResults(results: android.os.Bundle?) { val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); finish(if (text.isNullOrBlank()) result("no-match") else result().put("text", text)) }
      override fun onPartialResults(partialResults: android.os.Bundle?) { if (recognizing === task) partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text -> runCatching { emit(JSONObject().put("appId", appId).put("requestId", task.id).put("kind", "speech").put("text", text)) } } }
      override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    })
    try { engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)) } catch (_: Exception) { finish(result("unavailable")) }
  }
  private fun stopRecognition(error: String = "cancel") {
    val task = recognizing ?: return
    recognizing = null; val engine = recognizer; recognizer = null; runCatching { engine?.cancel(); engine?.destroy() }; complete(task, result(error))
  }

  private val sensorTypes = mapOf("accelerometer" to Sensor.TYPE_ACCELEROMETER, "gyroscope" to Sensor.TYPE_GYROSCOPE, "gravity" to Sensor.TYPE_GRAVITY, "rotation" to Sensor.TYPE_ROTATION_VECTOR)
  private fun startSensor(input: JSONObject, appId: String, emit: (JSONObject) -> Unit): JSONObject {
    val key = "$appId:${id(input)}"; stopSensor(key)
    if (sensors.size >= 16 || sensors.values.count { it.first == appId } >= 4) return result("busy")
    val kind = input.getString("kind"); val type = sensorTypes[kind] ?: return result("unsupported")
    val manager = context().getSystemService(SensorManager::class.java) ?: return result("unsupported")
    val sensor = manager.getDefaultSensor(type) ?: return result("unsupported")
    val interval = input.optInt("intervalMs", 50).coerceIn(20, 1000)
    var last = 0L
    val listener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        if (event.timestamp - last < interval * 1_000_000L) return
        last = event.timestamp
        val values = JSONArray(); event.values.take(5).forEach { values.put(it.toDouble()) }
        runCatching { emit(JSONObject().put("appId", appId).put("requestId", id(input)).put("kind", "sensor")
          .put("sensor", kind).put("values", values).put("timestampMs", event.timestamp / 1_000_000.0).put("accuracy", event.accuracy)) }
      }
      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    if (!manager.registerListener(listener, sensor, interval * 1000, main)) return result("unsupported")
    sensors[key] = appId to listener
    return result()
  }
  private fun stopSensor(key: String) { sensors.remove(key)?.let { context().getSystemService(SensorManager::class.java)?.unregisterListener(it.second) } }

  private fun biometricStatus(): JSONObject {
    if (Build.VERSION.SDK_INT == 28) return result().put("supported", true).put("status", -1) // API 28 has no public canAuthenticate query.
    val code = if (Build.VERSION.SDK_INT >= 30) context().getSystemService(BiometricManager::class.java).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
      else if (Build.VERSION.SDK_INT >= 29) context().getSystemService(BiometricManager::class.java).canAuthenticate()
      else 12
    return result().put("supported", Build.VERSION.SDK_INT >= 28).put("available", code == 0).put("status", code)
  }
  private fun authenticate(input: JSONObject, appId: String, reply: (JSONObject) -> Unit) {
    if (biometric != null) { reply(result("busy")); return }
    val activity = ShellCapabilities.hostActivity() ?: run { reply(result("unavailable")); return }
    if (Build.VERSION.SDK_INT < 28) { reply(result("unsupported")); return }
    val task = Task(appId, id(input), reply)
    val title = input.optString("title", "验证身份").trim().also { require(it.isNotEmpty() && it.length <= 80) }
    val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(input.optString("subtitle").take(200))
    if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    else builder.setNegativeButton("取消", activity.mainExecutor) { _, _ -> if (biometric === task) stopBiometric() }
    val signal = CancellationSignal(); biometric = task; biometricCancel = signal
    val timeout = Runnable { if (biometric === task) stopBiometric("timeout") }; main.postDelayed(timeout, 60_000)
    task.timeout = timeout
    fun finish(error: String?) { if (biometric === task) { biometric = null; biometricCancel = null; complete(task, result(error)) } }
    try { builder.build().authenticate(signal, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { finish(null) }
      override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { finish(if (errorCode in listOf(BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED)) "cancel" else "unavailable") }
    }) } catch (_: Exception) { finish("unavailable") }
  }
  private fun stopBiometric(error: String = "cancel") {
    val task = biometric ?: return
    biometric = null; biometricCancel?.cancel(); biometricCancel = null; complete(task, result(error))
  }
  private fun stopApp(appId: String, includeBiometric: Boolean = true) {
    awake.remove(appId); updateAwake()
    stopSpeech(appId)
    if (recognizing?.appId == appId) stopRecognition()
    if (includeBiometric && biometric?.appId == appId) stopBiometric()
    sensors.filterValues { it.first == appId }.keys.toList().forEach { stopSensor(it) }
  }
  fun stopAll(includeBiometric: Boolean = true) {
    main.post { awake.clear(); updateAwake(); stopSpeech(); stopRecognition(); if (includeBiometric) stopBiometric(); sensors.keys.toList().forEach { stopSensor(it) }; speechGeneration++; tts?.shutdown(); tts = null; ttsReady = false }
  }
}
