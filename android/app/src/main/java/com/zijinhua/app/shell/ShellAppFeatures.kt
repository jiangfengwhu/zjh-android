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
    "sensorStatus", "sensorStart", "sensorStop", "biometricStatus", "biometricAuthenticate", "biometricCancel", "appNativeStop", "appNativeVisibility", "removeApplicationShortcuts", "speechVoices", "speechLanguages", "speechQueryCancel")
  private val main = Handler(Looper.getMainLooper())
  private val sensorThread = android.os.HandlerThread("application-sensors").apply { start() }
  private val sensorHandler = Handler(sensorThread.looper)
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
  private val voiceTasks = mutableMapOf<String, Pair<Task, TextToSpeech>>()
  private val languageTasks = mutableMapOf<String, Pair<Task, SpeechRecognizer>>()
  private val awake = mutableSetOf<String>()
  private fun updateAwake() { ShellCapabilities.hostActivity()?.window?.let { window -> if (awake.isEmpty()) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
  private abstract class ActiveSensor : SensorEventListener { val active = java.util.concurrent.atomic.AtomicBoolean(true) }
  private val sensors = mutableMapOf<String, Pair<String, ActiveSensor>>()
  private fun context() = applicationContext ?: ShellCapabilities.hostContext()
  private fun resolve(task: Task, value: JSONObject) { if (!task.replied) { task.replied = true; task.reply(value) } }
  private fun complete(task: Task, value: JSONObject) { task.timeout?.let { main.removeCallbacks(it) }; task.timeout = null; resolve(task, value) }
  private fun result(error: String? = null) = JSONObject().put("ok", error == null).apply { if (error != null) put("error", error) }
  private fun app(input: JSONObject): String = input.getString("appId").also { require(it.matches(Regex("[a-z][a-z0-9-]{0,63}"))) }
  private fun id(input: JSONObject) = input.getString("requestId").also { require(it.matches(Regex("[A-Za-z0-9._:-]{1,160}"))) }

  fun call(method: String, input: JSONObject, emit: (JSONObject) -> Unit, reply: (JSONObject) -> Unit, isCurrent: () -> Boolean = { true }) {
    if (!isCurrent()) return
    if (method == "saveToGallery") {
      try { reply(saveToGallery(input)) } catch (_: Exception) { reply(result("file")) }
      return
    }
    val shortcutIcon = if (method == "shortcutAdd") {
      try { shortcutIcon(input) } catch (_: Exception) { reply(result("file")); return }
    } else null
    main.post {
      if (!isCurrent()) return@post
      try {
        val appId = app(input)
        if (method in setOf("shortcutAdd", "calendarCompose", "speechSpeak", "speechRecognize", "biometricAuthenticate", "sensorStart") && !ShellVisibility.active(appId)) { reply(result("cancel")); return@post }
        when (method) {
          "appNativeVisibility" -> { ShellVisibility.set(appId, input.optBoolean("active")); reply(result()) }
          "shortcutStatus" -> reply(result().put("supported", context().getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true))
          "shortcutList" -> reply(result().put("items", JSONArray(context().getSystemService(ShortcutManager::class.java)?.pinnedShortcuts?.filter { it.id.startsWith("$appId:") }?.map { JSONObject().put("id", it.id.substringAfter(':')).put("enabled", it.isEnabled).put("label", it.shortLabel) } ?: emptyList<JSONObject>())))
          "appKeepAwake" -> { if (input.optBoolean("on")) { if (!ShellVisibility.active(appId)) { reply(result("cancel")); return@post }; awake.add(appId) } else awake.remove(appId); updateAwake(); reply(result()) }
          "shortcutAdd" -> reply(shortcut(input, appId, requireNotNull(shortcutIcon)))
          "shortcutRemove" -> { removeShortcut(appId, input.getString("id")); reply(result()) }
          "removeApplicationShortcuts" -> { removeShortcuts(appId); reply(result()) }
          "calendarCompose" -> reply(calendar(input))
          "speechStatus" -> reply(result().put("recognition", SpeechRecognizer.isRecognitionAvailable(context()))
            .put("onDevice", Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context()))
            .put("microphone", ContextCompat.checkSelfPermission(context(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .put("tts", context().packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()))
          "speechSpeak" -> speak(input, appId, emit, reply)
          "speechVoices" -> voices(input, appId, reply)
          "speechLanguages" -> languages(input, appId, reply)
          "speechQueryCancel" -> { cancelSpeechQueries(appId, id(input)); reply(result()) }
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
    val packageRoot = File(context().filesDir, "application-bundles/$appId").canonicalFile
    require((file.path.startsWith(root.path + File.separator) || file.path.startsWith(packageRoot.path + File.separator)) && file.isFile)
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
      val options = BitmapFactory.Options().apply { inSampleSize = 1; while (bounds.outWidth / inSampleSize > 256 || bounds.outHeight / inSampleSize > 256) inSampleSize *= 2 }
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

  private fun cancelSpeechQueries(appId: String, requestId: String? = null) {
    voiceTasks.values.filter { it.first.appId == appId && (requestId == null || it.first.id == requestId) }.toList().forEach { (task, engine) -> voiceTasks.remove(task.id); engine.shutdown(); complete(task, result("cancel")) }
    languageTasks.values.filter { it.first.appId == appId && (requestId == null || it.first.id == requestId) }.toList().forEach { (task, engine) -> languageTasks.remove(task.id); engine.destroy(); complete(task, result("cancel")) }
  }
  private fun languages(input: JSONObject, appId: String, reply: (JSONObject) -> Unit) {
    if (Build.VERSION.SDK_INT < 33) { reply(result("unsupported")); return }
    if (languageTasks.size >= 2) { reply(result("busy")); return }
    val onDevice = input.optBoolean("onDevice")
    if (onDevice && !SpeechRecognizer.isOnDeviceRecognitionAvailable(context()) || !onDevice && !SpeechRecognizer.isRecognitionAvailable(context())) { reply(result("unsupported")); return }
    val timeoutMs = input.optLong("timeoutMs", 8000).also { require(it in 1000..180000) }
    val task = Task(appId, id(input), reply)
    val engine = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context()) else SpeechRecognizer.createSpeechRecognizer(context())
    languageTasks[task.id] = task to engine
    fun finish(value: JSONObject) { languageTasks.remove(task.id); engine.destroy(); complete(task, value) }
    task.timeout = Runnable { finish(result("timeout")) }; main.postDelayed(task.timeout!!, timeoutMs)
    try { engine.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), context().mainExecutor,
      object : android.speech.RecognitionSupportCallback {
        override fun onSupportResult(support: android.speech.RecognitionSupport) { if (!task.replied) finish(result().put("installed", JSONArray(support.installedOnDeviceLanguages)).put("supported", JSONArray(support.supportedOnDeviceLanguages)).put("online", JSONArray(support.onlineLanguages))) }
        override fun onError(error: Int) { if (!task.replied) finish(result(if (error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) "unsupported" else "unavailable")) }
      }) } catch (_: Exception) { finish(result("unavailable")) }
  }
  private fun voices(input: JSONObject, appId: String, reply: (JSONObject) -> Unit) {
    if (voiceTasks.size >= 2) { reply(result("busy")); return }
    val timeoutMs = input.optLong("timeoutMs", 8000).also { require(it in 1000..180000) }
    val task = Task(appId, id(input), reply)
    var engine: TextToSpeech? = null
    fun finish(value: JSONObject) { voiceTasks.remove(task.id); engine?.shutdown(); complete(task, value) }
    task.timeout = Runnable { finish(result("timeout")) }; main.postDelayed(task.timeout!!, timeoutMs)
    try { engine = TextToSpeech(context()) { code -> main.post {
      if (task.replied) return@post
      if (code != TextToSpeech.SUCCESS) { finish(result("unavailable")); return@post }
      try { val items = engine?.voices?.sortedBy { it.name }?.map { voice ->
        JSONObject().put("id", voice.name).put("language", voice.locale.toLanguageTag()).put("networkRequired", voice.isNetworkConnectionRequired).put("quality", voice.quality)
      } ?: emptyList()
      finish(result().put("items", JSONArray(items)))
      } catch (_: Exception) { finish(result("unavailable")) }
    } }
    voiceTasks[task.id] = task to engine!!
    } catch (_: Exception) { finish(result("unavailable")) }
  }
  private fun speak(input: JSONObject, appId: String, emit: (JSONObject) -> Unit, reply: (JSONObject) -> Unit) {
    val text = input.getString("text").also { require(it.isNotBlank() && it.length <= 100000) }
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
    main.postDelayed(timeout, input.optLong("timeoutMs", 600000).also { require(it in 1000..1800000) })
    val chunks = mutableListOf<String>()
    val maximum = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(2)
    var offset = 0
    while (offset < text.length) {
      var end = (offset + maximum).coerceAtMost(text.length)
      if (end < text.length) {
        if (Character.isHighSurrogate(text[end - 1])) end--
        val breakAt = (end - 1 downTo (offset + maximum / 2)).firstOrNull { text[it] in "\n。！？.!?;；" }
        if (breakAt != null) end = breakAt + 1
      }
      chunks.add(text.substring(offset, end)); offset = end
    }
    var chunk = 0
    fun start() {
      if (speaking !== task) return
      val engine = tts ?: return
      val selectedVoice = input.optString("voice")
      if (selectedVoice.isNotEmpty()) {
        val voice = engine.voices?.firstOrNull { it.name == selectedVoice }
        if (voice == null || engine.setVoice(voice) < 0) { stopSpeech(appId, "language"); return }
      } else {
        val languageResult = engine.setLanguage(Locale.forLanguageTag(language))
        if (languageResult < 0) { stopSpeech(appId, if (languageResult == TextToSpeech.LANG_MISSING_DATA) "missing-data" else "language"); return }
      }
      engine.setSpeechRate(rate.toFloat()); engine.setPitch(pitch.toFloat())
      fun queueChunk() {
        if (speaking !== task) return
        if (engine.speak(chunks[chunk], TextToSpeech.QUEUE_FLUSH, null, "${task.id}:$chunk") == TextToSpeech.ERROR) stopSpeech(appId, "speech")
      }
      engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { main.post { if (speaking === task && utteranceId == "${task.id}:$chunk") emit(JSONObject().put("appId", appId).put("requestId", task.id).put("kind", "speech-state").put("state", "speaking")) } }
        override fun onDone(utteranceId: String?) { main.post {
          if (speaking === task && utteranceId == "${task.id}:$chunk") {
            chunk++
            if (chunk < chunks.size) queueChunk() else { speaking = null; complete(task, result()) }
          }
        } }
        @Deprecated("Platform callback") override fun onError(utteranceId: String?) { main.post { if (speaking === task && utteranceId == "${task.id}:$chunk") stopSpeech(appId, "speech") } }
      })
      queueChunk()
      if (speaking === task && !input.optBoolean("waitForCompletion", true)) resolve(task, result())
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
      private fun state(value: String) { if (recognizing === task) emit(JSONObject().put("appId", appId).put("requestId", task.id).put("kind", "speech-state").put("state", value)) }
      private var lastLevel = 0L
      override fun onReadyForSpeech(params: android.os.Bundle?) { state("listening") }
      override fun onBeginningOfSpeech() { state("hearing") }
      override fun onRmsChanged(rmsdB: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (recognizing === task && rmsdB.isFinite() && now - lastLevel >= 80) { lastLevel = now; emit(JSONObject().put("appId", appId).put("requestId", task.id).put("kind", "speech-volume").put("level", rmsdB.toDouble())) }
      }
      override fun onBufferReceived(buffer: ByteArray?) = Unit
      override fun onEndOfSpeech() { state("processing") }
      override fun onError(error: Int) { finish(result(when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_NO_MATCH -> "no-match"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "missing-data"
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
    val listener = object : ActiveSensor() {
      override fun onSensorChanged(event: SensorEvent) {
        if (!active.get() || event.timestamp - last < interval * 1_000_000L) return
        last = event.timestamp
        val values = JSONArray(); event.values.take(5).forEach { values.put(it.toDouble()) }
        runCatching { emit(JSONObject().put("appId", appId).put("requestId", id(input)).put("kind", "sensor")
          .put("sensor", kind).put("values", values).put("timestampMs", event.timestamp / 1_000_000.0).put("accuracy", event.accuracy)) }
      }
      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    if (!manager.registerListener(listener, sensor, interval * 1000, sensorHandler)) return result("unsupported")
    sensors[key] = appId to listener
    return result()
  }
  private fun stopSensor(key: String) { sensors.remove(key)?.let { it.second.active.set(false); context().getSystemService(SensorManager::class.java)?.unregisterListener(it.second) } }

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
    cancelSpeechQueries(appId)
    awake.remove(appId); updateAwake()
    stopSpeech(appId)
    if (recognizing?.appId == appId) stopRecognition()
    if (includeBiometric && biometric?.appId == appId) stopBiometric()
    sensors.filterValues { it.first == appId }.keys.toList().forEach { stopSensor(it) }
  }
  fun stopAll(includeBiometric: Boolean = true) {
    main.post { awake.clear(); updateAwake(); stopSpeech(); stopRecognition(); if (includeBiometric) stopBiometric(); sensors.keys.toList().forEach { stopSensor(it) }; (voiceTasks.values.map { it.first.appId } + languageTasks.values.map { it.first.appId }).toSet().forEach { cancelSpeechQueries(it) }; speechGeneration++; tts?.shutdown(); tts = null; ttsReady = false }
  }
}
