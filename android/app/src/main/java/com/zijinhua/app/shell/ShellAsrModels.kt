package com.zijinhua.app.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.zijinhua.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Device-wide, optional model files. URLs and destination names are build-owned. */
object ShellAsrModels {
  val methods = setOf("asrModelStatus", "asrModelDownload", "asrModelCancel", "asrModelDelete")
  private val main = Handler(Looper.getMainLooper())
  private val metadata = Executors.newSingleThreadExecutor { Thread(it, "asr-model-metadata") }
  private val downloads = Executors.newSingleThreadExecutor { Thread(it, "asr-model-download") }
  private val control = Executors.newSingleThreadExecutor { Thread(it, "asr-model-control") }
  private val observers = ConcurrentHashMap.newKeySet<(JSONObject) -> Unit>()
  private val files = JSONObject(BuildConfig.ASR_MODEL_FILES).let { json -> json.keys().asSequence().associateWith { json.getLong(it) } }
  private val total = files.values.sum()
  @Volatile private var task: Download? = null
  @Volatile private var deleting = false
  @Volatile private var error: String? = null
  private class Download {
    val cancelled = AtomicBoolean(false)
    val received = AtomicLong(0)
    val replies = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    @Volatile var connection: HttpURLConnection? = null
  }
  private fun root(context: Context) = File(context.noBackupFilesDir, "asr-models/${BuildConfig.ASR_MODEL_ID}")
  fun file(context: Context, name: String): File { require(name in files); return File(root(context), name) }
  fun ready(context: Context) = !deleting && files.all { (name, bytes) -> file(context, name).let { it.isFile && it.length() == bytes } }
  fun changing() = deleting
  fun changed(context: Context) { notify(context) }
  fun observe(listener: (JSONObject) -> Unit) { observers.add(listener) }
  fun unobserve(listener: (JSONObject) -> Unit) { observers.remove(listener) }
  private fun stored(context: Context) = files.entries.sumOf { (name, bytes) -> file(context, name).let { if (it.isFile && it.length() == bytes) bytes else 0 } }
  private fun status(context: Context): JSONObject {
    val current = task
    return JSONObject().put("ok", true).put("modelId", BuildConfig.ASR_MODEL_ID).put("ready", ready(context))
      .put("downloading", current != null).put("cancelling", current?.cancelled?.get() == true).put("deleting", deleting)
      .put("received", current?.received?.get() ?: stored(context)).put("total", total).put("storedBytes", stored(context))
      .put("inUse", ShellAsr.busy()).apply { error?.let { put("error", it) } }
  }
  private fun notify(context: Context) { metadata.execute { val value = status(context); observers.forEach { runCatching { it(value) } } } }
  fun call(context: Context, method: String, reply: (JSONObject) -> Unit) {
    when (method) {
      "asrModelStatus" -> metadata.execute { reply(status(context)) }
      "asrModelCancel" -> { cancel(); reply(JSONObject().put("ok", true)); notify(context) }
      "asrModelDownload" -> main.post {
        if (!ShellVisibility.foreground) { reply(JSONObject().put("ok", false).put("error", "cancel")); return@post }
        if (deleting || task?.cancelled?.get() == true) { reply(JSONObject().put("ok", false).put("error", "busy")); return@post }
        task?.let { it.replies.add(reply); return@post }
        val current = Download(); current.replies.add(reply); task = current; error = null
        notify(context)
        downloads.execute { download(context, current) }
      }
      "asrModelDelete" -> main.post {
        if (task != null || deleting || ShellAsr.busy()) { reply(JSONObject().put("ok", false).put("error", "busy")); return@post }
        deleting = true; error = null; notify(context)
        ShellAsr.releaseModel { released -> metadata.execute {
          try {
            check(released)
            for (name in files.keys) for (suffix in listOf("", ".tmp")) {
              val candidate = File(root(context), name + suffix)
              if (candidate.exists() && !candidate.delete()) throw IOException("模型文件暂时无法删除")
            }
            root(context).delete()
            deleting = false; reply(status(context))
          } catch (_: Exception) { deleting = false; error = "模型暂时无法删除，请稍后重试"; reply(JSONObject().put("ok", false).put("error", error)) }
          notify(context)
        } }
      }
    }
  }
  fun cancel() {
    val current = task ?: return
    current.cancelled.set(true)
    control.execute { current.connection?.disconnect() }
  }
  private fun download(context: Context, current: Download) {
    var outcome = JSONObject().put("ok", false)
    val started = SystemClock.elapsedRealtime()
    var lastProgress = 0L
    try {
      check(root(context).isDirectory || root(context).mkdirs())
      var completed = stored(context); current.received.set(completed)
      for ((name, bytes) in files) {
        if (current.cancelled.get()) throw IOException("cancel")
        val destination = file(context, name)
        if (destination.isFile && destination.length() == bytes) continue
        val temporary = File(root(context), "$name.tmp")
        val connection = (URL("https://zjh-cdn.297782.xyz/v1/asr/${BuildConfig.ASR_MODEL_ID}/$name").openConnection() as HttpURLConnection).apply {
          connectTimeout = 10000; readTimeout = 15000; instanceFollowRedirects = false
          setRequestProperty("Accept-Encoding", "identity")
        }
        current.connection = connection
        try {
          if (current.cancelled.get()) throw IOException("cancel")
          if (connection.responseCode != 200 || connection.contentLengthLong >= 0 && connection.contentLengthLong != bytes) throw IOException("模型下载服务暂时不可用")
          var received = 0L
          connection.inputStream.use { input -> temporary.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              if (current.cancelled.get()) throw IOException("cancel")
              if (SystemClock.elapsedRealtime() - started > 600_000) throw IOException("模型下载超时，请重试")
              val count = input.read(buffer); if (count < 0) break
              received += count; if (received > bytes) throw IOException("模型文件长度不正确")
              output.write(buffer, 0, count); current.received.set(completed + received)
              val now = SystemClock.elapsedRealtime()
              if (now - lastProgress >= 250) { lastProgress = now; notify(context) }
            }
          } }
          if (received != bytes) throw IOException("模型下载未完成，请重试")
          if (current.cancelled.get()) throw IOException("cancel")
          if (!temporary.renameTo(destination)) throw IOException("模型文件无法保存，请重试")
          completed += bytes; current.received.set(completed); notify(context)
        } finally { connection.disconnect(); current.connection = null; temporary.delete() }
      }
      if (current.cancelled.get()) throw IOException("cancel")
      outcome = JSONObject().put("ok", true)
    } catch (failure: Exception) {
      error = if (current.cancelled.get()) null else if (failure is IOException && failure.message?.startsWith("模型") == true) failure.message else "模型下载失败，请检查网络后重试"
      outcome.put("error", if (current.cancelled.get()) "cancel" else error)
    } finally {
      main.post {
        if (task === current) task = null
        current.replies.forEach { runCatching { it(outcome) } }; current.replies.clear()
        notify(context)
      }
    }
  }
}
