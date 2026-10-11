package com.zijinhua.app.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Copies transient content grants while the sending Activity is alive; JS receives only app-owned paths. */
object ShellIncomingShares {
  private val io = Executors.newFixedThreadPool(2)
  private val closing = Executors.newSingleThreadExecutor()
  private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
  private val preparing = ConcurrentHashMap<String, JSONObject>()
  private class Transfer {
    val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
    val signal = android.os.CancellationSignal()
    val reader = java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>()
    val writer = java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>()
    fun cancel() { cancelled.set(true); closing.execute { signal.cancel(); runCatching { reader.get()?.close() }; runCatching { writer.get()?.close() } } }
  }
  private val transfers = ConcurrentHashMap<String, Transfer>()
  private val storeLock = Any()
  private const val lifetime = 3L * 86400000
  private fun root(context: Context) = File(context.filesDir, "incoming-shares")
  private fun prefs(context: Context) = context.getSharedPreferences("incoming-shares", Context.MODE_PRIVATE)
  private fun rows(context: Context): List<JSONObject> {
    val array = JSONArray(prefs(context).getString("items", "[]") ?: "[]")
    return (0 until array.length()).map { array.getJSONObject(it) }
  }
  private fun save(context: Context, rows: List<JSONObject>) { check(prefs(context).edit().putString("items", JSONArray(rows).toString()).commit()) }
  private fun changed(context: Context) = ShellNativeEvents.emit(context, "zijinhua.incoming.share", JSONObject())
  private fun clean(context: Context) {
    val now = System.currentTimeMillis()
    var current = rows(context)
    var recovered = false
    current = current.map { row ->
      val owner = row.optString("targetAppId")
      if (owner.isEmpty()) row else {
        recovered = true
        val id = row.getString("id")
        val destination = File(context.filesDir, "applications/$owner/imports/shared-$id")
        val repaired = JSONObject(row.toString()).apply { remove("targetAppId") }
        if (destination.isDirectory || repaired.getJSONArray("files").length() == 0) {
          repaired.put("appId", owner)
          val files = repaired.getJSONArray("files")
          for (index in 0 until files.length()) files.getJSONObject(index).apply { put("path", "imports/shared-$id/${getInt("index")}"); remove("index") }
        }
        repaired
      }
    }
    val live = current.filter { it.optLong("createdAt") > now - lifetime }
    if (recovered || live.size != current.size) save(context, live)
    val keep = live.filter { !it.has("appId") }.map { it.getString("id") }.toSet() + preparing.keys
    root(context).listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
  }
  @Suppress("DEPRECATION")
  fun capture(context: Context, intent: Intent?) {
    if (intent == null || intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
    val text = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)?.toString()?.take(32768)
    val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.take(200)
    val uris = mutableListOf<Uri>()
    if (intent.action == Intent.ACTION_SEND_MULTIPLE) uris.addAll(intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList())
    else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
    intent.clipData?.let { clip -> for (i in 0 until minOf(clip.itemCount, 17)) clip.getItemAt(i).uri?.let { uris.add(it) } }
    val selected = uris.distinct()
    intent.action = Intent.ACTION_MAIN; intent.removeExtra(Intent.EXTRA_TEXT); intent.removeExtra(Intent.EXTRA_STREAM); intent.clipData = null
    if (selected.isEmpty() && text.isNullOrBlank()) return
    val app = context.applicationContext
    val id = UUID.randomUUID().toString()
    val item = JSONObject().put("id", id).put("createdAt", System.currentTimeMillis()).put("files", JSONArray()).put("preparing", true)
    text?.let { item.put("text", it) }; title?.let { item.put("title", it) }
    if (preparing.size >= 2) return
    val transfer = Transfer(); transfers[id] = transfer
    preparing[id] = item; changed(app)
    val deadline = deadlines.schedule({ transfer.timedOut.set(true); transfer.cancel() }, 120, java.util.concurrent.TimeUnit.SECONDS)
    io.execute {
      val directory = File(root(app), id)
      try {
        check(!transfer.cancelled.get())
        synchronized(storeLock) { clean(app); check(rows(app).count { !it.has("appId") } < 8) }
        require(selected.size <= 16)
        val files = JSONArray(); var total = 0L
        selected.forEachIndexed { index, uri ->
          require(uri.scheme == "content")
          var name = "shared-$index"
          check(!transfer.cancelled.get())
          app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, transfer.signal)?.use { cursor ->
            if (cursor.moveToFirst()) {
              val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
              if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) require(cursor.getLong(sizeIndex) <= 100L * 1024 * 1024)
              val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (nameIndex >= 0) name = cursor.getString(nameIndex)?.take(200) ?: name
            }
          }
          directory.mkdirs()
          val file = File(directory, index.toString())
          val stream = app.contentResolver.openAssetFileDescriptor(uri, "r", transfer.signal)?.createInputStream() ?: error("file")
          transfer.reader.set(stream)
          stream.use { inputStream -> check(!transfer.cancelled.get()); file.outputStream().use { output ->
            transfer.writer.set(output)
            val buffer = ByteArray(64 * 1024)
            while (true) {
              check(!transfer.cancelled.get())
              val count = inputStream.read(buffer); if (count < 0) break
              total += count; require(total <= 100L * 1024 * 1024); output.write(buffer, 0, count)
            }
          } }
          transfer.reader.set(null); transfer.writer.set(null)
          files.put(JSONObject().put("name", name).put("mime", app.contentResolver.getType(uri) ?: "application/octet-stream").put("bytes", file.length()).put("index", index))
        }
        val ready = JSONObject(item.toString()).apply { remove("preparing"); put("files", files) }
        synchronized(storeLock) {
          check(!transfer.cancelled.get())
          val current = rows(app)
          check(current.count { !it.has("appId") } < 8 && current.size < 64)
          save(app, current + ready)
        }
      } catch (_: Exception) {
        directory.deleteRecursively()
        val failed = JSONObject(item.toString()).apply { remove("preparing"); put("error", if (transfer.timedOut.get()) "接收文件超时，请重新分享" else "无法接收分享文件，请重新分享；最多16个文件、合计100 MB") }
        runCatching { synchronized(storeLock) {
          if (!transfer.cancelled.get() || transfer.timedOut.get()) { val current = rows(app); if (current.size < 64) save(app, current + failed) }
        } }
      } finally { deadline.cancel(false); transfers.remove(id); preparing.remove(id); changed(app) }
    }
  }
  fun call(context: Context, method: String, input: JSONObject): JSONObject = synchronized(storeLock) {
    clean(context)
    val current = rows(context)
    if (method == "incomingSharePending") return@synchronized ShellOperations.result().put("items", JSONArray(current.filter { !it.has("appId") } + preparing.values))
    val appId = input.optString("appId")
    if (method == "incomingShareList") {
      require(appId.matches(Regex("[a-z][a-z0-9-]{0,63}")))
      return@synchronized ShellOperations.result().put("items", JSONArray(current.filter { it.optString("appId") == appId }))
    }
    if (method == "incomingShareRemoveApp") {
      save(context, current.filterNot { it.optString("appId") == appId }); return@synchronized ShellOperations.result()
    }
    val id = input.getString("id").also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    val transfer = if (method == "incomingShareDiscard") transfers[id] else null
    transfer?.let { it.timedOut.set(false); it.cancel(); preparing.remove(id); changed(context) }
    val item = current.firstOrNull { it.getString("id") == id } ?: return@synchronized ShellOperations.result(if (transfer != null) null else "not-found")
    when (method) {
      "incomingShareDiscard" -> {
        require(!item.has("appId")); save(context, current.filterNot { it === item }); File(root(context), id).deleteRecursively(); changed(context); ShellOperations.result()
      }
      "incomingShareAcknowledge" -> {
        require(item.optString("appId") == appId); save(context, current.filterNot { it === item }); ShellOperations.result()
      }
      "incomingShareAccept" -> {
        require(appId.matches(Regex("[a-z][a-z0-9-]{0,63}")))
        if (item.has("appId")) return@synchronized if (item.getString("appId") == appId) ShellOperations.result().put("item", item) else ShellOperations.result("unavailable")
        require(!item.has("error"))
        if (current.count { it.optString("appId") == appId } >= 16) return@synchronized ShellOperations.result("busy")
        val destination = File(context.filesDir, "applications/$appId/imports/shared-$id")
        val source = File(root(context), id)
        val accepted = JSONObject(item.toString()).put("appId", appId)
        val files = accepted.getJSONArray("files")
        for (index in 0 until files.length()) { files.getJSONObject(index).apply { put("path", "imports/shared-$id/${getInt("index")}"); remove("index") } }
        // Rename on the same filesDir filesystem; never copy large data through the JS bridge.
        save(context, current.map { if (it === item) JSONObject(item.toString()).put("targetAppId", appId) else it })
        if (files.length() > 0) { destination.parentFile!!.mkdirs(); check(source.renameTo(destination)) }
        try { save(context, current.map { if (it === item) accepted else it }) }
        catch (failure: Exception) { if (files.length() > 0) destination.renameTo(source); throw failure }
        changed(context); ShellOperations.result().put("item", accepted)
      }
      else -> ShellOperations.result("unsupported")
    }
  }
}
