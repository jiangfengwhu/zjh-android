package com.zijinhua.app.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/** One foreground microphone session; capture never waits for model inference. */
object ShellAsr {
  private const val RATE = 16000
  private const val CHUNK = 1600
  private val main = Handler(Looper.getMainLooper())
  private val capture = Executors.newSingleThreadExecutor { Thread(it, "asr-capture") }
  private val inference = Executors.newSingleThreadExecutor { Thread(it, "asr-inference") }
  @Volatile private var current: Session? = null
  // Accessed only by inference; reused briefly between explicit recording sessions.
  private var cached: OnlineRecognizer? = null
  private val releaseIdle = Runnable { inference.execute { if (current == null) { cached?.release(); cached = null } } }
  private class Failure(val code: String) : Exception(code)
  private class Session(val appId: String, val id: String, val emit: (JSONObject) -> Unit, val reply: (JSONObject) -> Unit, val recording: File?) {
    val stop = AtomicBoolean(false)
    val cancelled = AtomicBoolean(false)
    val replied = AtomicBoolean(false)
    val audio = ArrayBlockingQueue<FloatArray>(128)
    @Volatile var captureDone = false
    @Volatile var failure: String? = null
  }
  private fun result(error: String? = null) = JSONObject().put("ok", error == null).apply { if (error != null) put("error", error) }
  fun available(context: Context) = ShellAsrModels.ready(context)
  fun busy() = current != null
  fun releaseModel(done: (Boolean) -> Unit) {
    main.removeCallbacks(releaseIdle)
    inference.execute { val released = runCatching { check(current == null); cached?.release(); cached = null }.isSuccess; done(released) }
  }
  fun languages(context: Context) = result().put("installed", JSONArray(if (available(context)) listOf("zh-CN", "en-US") else emptyList<String>())).put("supported", JSONArray(listOf("zh-CN", "en-US"))).put("online", JSONArray())
  fun recognize(context: Context, input: JSONObject, appId: String, emit: (JSONObject) -> Unit, reply: (JSONObject) -> Unit) {
    if (current != null || ShellAsrModels.changing()) { reply(result("busy")); return }
    if (!ShellVisibility.active(appId)) { reply(result("cancel")); return }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { reply(result("microphone-permission")); return }
    val language = input.optString("language", "zh-CN").replace('_', '-').lowercase()
    if (language != "zh" && language != "en" && !language.startsWith("zh-") && !language.startsWith("en-")) { reply(result("language")); return }
    if (!available(context)) { reply(result("missing-data")); return }
    val id = input.getString("requestId").also { require(it.matches(Regex("[A-Za-z0-9._:-]{1,160}"))) }
    val recording = input.optString("recordingFile").takeIf { it.isNotBlank() }?.let { uri ->
      val root = File(context.filesDir, "applications/$appId").canonicalFile
      File(android.net.Uri.parse(uri).path ?: "").canonicalFile.also {
        require(uri.startsWith("file://") && it.path.startsWith(root.path + File.separator) && it.extension == "wav" && !it.exists())
      }
    }
    val session = Session(appId, id, emit, reply, recording)
    current = session; main.removeCallbacks(releaseIdle)
    ShellAsrModels.changed(context)
    inference.execute { run(context.applicationContext, session) }
  }
  fun finish(appId: String) { current?.takeIf { it.appId == appId }?.stop?.set(true) }
  fun cancel(appId: String? = null, requestId: String? = null) {
    val session = current ?: return
    if (appId != null && session.appId != appId || requestId != null && session.id != requestId) return
    session.cancelled.set(true); session.stop.set(true)
    if (session.replied.compareAndSet(false, true)) session.reply(result("cancel"))
  }
  private fun event(session: Session, kind: String, key: String, value: Any) {
    if (current === session && !session.cancelled.get() && !session.replied.get()) {
      runCatching { session.emit(JSONObject().put("appId", session.appId).put("requestId", session.id).put("kind", kind).put(key, value)) }
    }
  }
  private fun recognizer(context: Context): OnlineRecognizer {
    cached?.let { return it }
    return OnlineRecognizer(config = OnlineRecognizerConfig(
      featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
      modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(encoder = ShellAsrModels.file(context, "encoder.int8.onnx").absolutePath, decoder = ShellAsrModels.file(context, "decoder.onnx").absolutePath, joiner = ShellAsrModels.file(context, "joiner.int8.onnx").absolutePath),
        tokens = ShellAsrModels.file(context, "tokens.txt").absolutePath, numThreads = 2, provider = "cpu", modelType = "zipformer2", debug = false,
      ),
      enableEndpoint = false,
    )).also { cached = it }
  }
  private fun run(context: Context, session: Session) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    var stream: OnlineStream? = null
    var value = result("cancel")
    var recording: WavCapture? = null
    var saved = false
    try {
      if (session.cancelled.get()) return
      recording = session.recording?.let { WavCapture(it) }
      // Begin capture immediately, retaining the first words during lazy model loading.
      if (!session.stop.get()) capture.execute { record(session) } else session.captureDone = true
      val engine = recognizer(context)
      if (session.cancelled.get()) return
      stream = engine.createStream()
      var published = ""
      var publishedAt = 0L
      var processing = false
      while (!session.cancelled.get()) {
        session.failure?.let { throw Failure(it) }
        val samples = session.audio.poll(50, TimeUnit.MILLISECONDS)
        if (samples != null) {
          recording?.write(samples)
          stream.acceptWaveform(samples, RATE)
          while (engine.isReady(stream) && !session.cancelled.get()) engine.decode(stream)
          val text = engine.getResult(stream).text
          if (text.length > 100000) throw Failure("too-large")
          val now = SystemClock.elapsedRealtime()
          if (text != published && now - publishedAt >= 120) { published = text; publishedAt = now; event(session, "speech", "text", text) }
        }
        if (session.stop.get() && !processing) { processing = true; event(session, "speech-state", "state", "processing") }
        if (session.captureDone && session.audio.isEmpty()) break
      }
      if (session.cancelled.get()) return
      session.failure?.let { throw Failure(it) }
      // Flush right context after the final recorded sample, on the inference thread.
      stream.acceptWaveform(FloatArray(RATE / 2), RATE)
      stream.inputFinished()
      while (engine.isReady(stream) && !session.cancelled.get()) engine.decode(stream)
      if (session.cancelled.get()) return
      val text = engine.getResult(stream).text.trim()
      if (text.length > 100000) throw Failure("too-large")
      event(session, "speech", "text", text)
      value = if (text.isBlank()) result("no-match") else result().put("text", text)
      if (text.isNotBlank() && recording != null && !session.cancelled.get()) {
        recording.finish(); saved = true; value.put("durationMs", recording.durationMs)
      }
    } catch (error: Exception) {
      value = result(if (error is Failure) error.code else "asr-engine")
    } catch (_: LinkageError) {
      value = result("asr-engine")
    } finally {
      session.stop.set(true)
      runCatching { stream?.release() }
      session.audio.clear()
      recording?.close()
      if (saved && session.cancelled.get()) session.recording?.delete()
      main.post {
        if (current === session) current = null
        ShellAsrModels.changed(context)
        if (saved && session.cancelled.get()) session.recording?.delete()
        if (session.replied.compareAndSet(false, true)) session.reply(value)
        main.removeCallbacks(releaseIdle); main.postDelayed(releaseIdle, 60_000)
      }
    }
  }
  /** Writes on the inference worker, never on the microphone/UI thread; no full recording in memory. */
  private class WavCapture(private val target: File) {
    private val temporary: File
    private val output: RandomAccessFile
    private var bytes = 0L
    private var closed = false
    init {
      check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
      temporary = File(target.parentFile, ".asr-${UUID.randomUUID()}.tmp")
      output = RandomAccessFile(temporary, "rw"); output.write(ByteArray(44))
    }
    val durationMs get() = bytes * 1000 / (RATE * 2)
    fun write(samples: FloatArray) {
      if (bytes + samples.size * 2 > 100 * 1024 * 1024) throw Failure("too-large")
      val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
      samples.forEach { pcm.putShort((it * 32768).toInt().coerceIn(-32768, 32767).toShort()) }
      output.write(pcm.array()); bytes += pcm.capacity()
    }
    fun finish() {
      val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      header.put("RIFF".toByteArray()).putInt((bytes + 36).toInt()).put("WAVEfmt ".toByteArray())
      header.putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
      header.put("data".toByteArray()).putInt(bytes.toInt())
      output.seek(0); output.write(header.array()); output.close(); closed = true
      java.nio.file.Files.move(temporary.toPath(), target.toPath())
    }
    fun close() { if (!closed) runCatching { output.close() }; temporary.delete() }
  }
  private fun record(session: Session) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    var recorder: AudioRecord? = null
    try {
      if (session.stop.get()) return
      val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      if (minimum <= 0) throw Failure("audio")
      recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, RATE))
      if (recorder.state != AudioRecord.STATE_INITIALIZED) throw Failure("audio")
      recorder.startRecording()
      if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw Failure("audio")
      event(session, "speech-state", "state", "listening")
      val buffer = ShortArray(CHUNK)
      var offset = 0; var heard = false
      var lastVolume = 0L
      while (!session.stop.get()) {
        val count = recorder.read(buffer, offset, CHUNK - offset, AudioRecord.READ_NON_BLOCKING)
        if (count < 0) throw Failure("audio")
        if (count == 0) { Thread.sleep(5); continue }
        offset += count
        if (offset == CHUNK) {
          val samples = FloatArray(CHUNK) { buffer[it] / 32768f }
          if (!session.audio.offer(samples)) throw Failure("asr-overload")
          val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
          val db = (20 * log10(maxOf(rms, 0.000001))).toFloat()
          val now = SystemClock.elapsedRealtime()
          if (now - lastVolume >= 100) { lastVolume = now; event(session, "speech-volume", "level", db) }
          if (!heard && db > -45f) { heard = true; event(session, "speech-state", "state", "hearing") }
          offset = 0
        }
      }
      if (offset > 0 && !session.cancelled.get() && !session.audio.offer(FloatArray(offset) { buffer[it] / 32768f })) throw Failure("asr-overload")
    } catch (error: Exception) {
      if (!session.cancelled.get()) session.failure = if (error is SecurityException) "microphone-permission" else if (error is Failure) error.code else "audio"
      session.stop.set(true)
    } finally {
      runCatching { recorder?.stop() }; recorder?.release(); session.captureDone = true
    }
  }
}
