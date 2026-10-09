package com.zijinhua.app.bundle

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.Future
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** Immutable application blobs. JS checks the public SDK contract before invoking this loader. */
class ApplicationStore(private val context: Context) {
  private val root = File(context.filesDir, "applications")

  private companion object {
    // Fixed stripes bound memory while keeping the same immutable blob serialized.
    val downloadLocks = Array(64) { Any() }
    val resources = Executors.newFixedThreadPool(6)
    val preparing = ConcurrentHashMap<String, Preparation>()
    val prepared = LinkedHashMap<String, PreparedApplication>(32, 0.75f, true)
    val watchdogs = Executors.newSingleThreadScheduledExecutor()
  }

  private class Transfer {
    val bytes = AtomicLong(0)
    var checkpoint = 0L
    var checkedAt = System.nanoTime()
  }
  private class Preparation {
    lateinit var task: FutureTask<PreparedApplication>
    val connections = ConcurrentHashMap<HttpURLConnection, Transfer>()
    val workers = CopyOnWriteArrayList<Future<*>>()
    val failure = AtomicReference<IOException>()
    fun check() {
      failure.get()?.let { throw it }
      if (Thread.currentThread().isInterrupted) throw IOException("作品下载已取消")
    }
    fun cancel(error: IOException) {
      if (!failure.compareAndSet(null, error)) return
      connections.keys.forEach { runCatching { it.disconnect() } }
      workers.forEach { it.cancel(true) }
    }
  }

  fun cancelPreparation(appId: String, releaseId: String) {
    preparing[root.absolutePath + "/" + appId + ":" + releaseId]?.cancel(IOException("作品下载已取消"))
  }

  data class PreparedApplication(val file: File, val assets: Map<String, String>)

  /** Preparation and execution share the exact same immutable paths. */
  fun prepare(manifest: JSONObject): PreparedApplication {
    val key = root.absolutePath + "/" + manifest.getString("appId") + ":" + manifest.getString("releaseId")
    val job = Preparation()
    job.task = FutureTask<PreparedApplication> {
      val started = System.nanoTime()
      val watchdog = watchdogs.scheduleAtFixedRate({
        val now = System.nanoTime()
        if (now - started >= TimeUnit.SECONDS.toNanos(90)) {
          job.cancel(SocketTimeoutException("作品下载超时，请检查网络后重试"))
        } else for (transfer in job.connections.values) {
          if (now - transfer.checkedAt < TimeUnit.SECONDS.toNanos(15)) continue
          val received = transfer.bytes.get()
          if (received - transfer.checkpoint < 32768) {
            job.cancel(SocketTimeoutException("作品下载进度过慢，请检查网络后重试")); break
          }
          transfer.checkpoint = received; transfer.checkedAt = now
        }
      }, 1, 1, TimeUnit.SECONDS)
      try {
        job.check()
        val result = downloadResources(manifest, job)
        job.check()
        synchronized(prepared) {
          prepared[key] = result
          while (prepared.size > 32) prepared.remove(prepared.keys.first())
        }
        result
      } catch (error: Exception) {
        val cause = job.failure.get() ?: IOException(error.cause?.message ?: error.message ?: "作品下载失败", error)
        job.cancel(cause)
        throw cause
      } finally {
        watchdog.cancel(false)
        job.connections.keys.forEach { runCatching { it.disconnect() } }
        job.workers.forEach { if (!it.isDone) it.cancel(true) }
      }
    }
    val flight = synchronized(prepared) {
      prepared[key]?.let {
        Log.i("ApplicationStore", "reuse prepared app=${manifest.getString("appId")} assets=${it.assets.size}")
        return it
      }
      preparing.putIfAbsent(key, job) ?: job
    }
    // A bridge worker coordinates the job; only file IO uses the resource pool.
    // Concurrent prepare/open calls join this job without queueing another one.
    if (flight === job) job.task.run()
    return try { flight.task.get() } catch (error: Exception) {
      throw (flight.failure.get() ?: error)
    } finally {
      if (flight.task.isDone) preparing.remove(key, flight)
    }
  }

  private fun downloadResources(manifest: JSONObject, job: Preparation): PreparedApplication {
    val appId = manifest.getString("appId")
    val releaseId = manifest.getString("releaseId")
    val entries = manifest.optJSONArray("assets")
    val count = (entries?.length() ?: 0) + 1
    val next = AtomicInteger(0)
    val bundle = AtomicReference<File>()
    val paths = ConcurrentHashMap<String, String>()
    // Two workers per application, six globally: the first two jobs are the
    // bundle and first asset, then assets continue concurrently without queuing
    // an application's entire resource list ahead of the following works.
    val workers = (0 until minOf(2, count)).map {
      resources.submit {
        while (true) {
          job.check()
          val index = next.getAndIncrement()
          if (index >= count) break
          if (index == 0) bundle.set(obtain(manifest, job))
          else {
            val asset = entries!!.getJSONObject(index - 1)
            val name = asset.getString("name")
            require(name.isNotEmpty() && !name.contains("..") && !name.startsWith("/") && !name.contains("\\")) { "invalid asset name" }
            val item = JSONObject().put("appId", appId).put("releaseId", releaseId).put("bundle", asset)
            paths[name] = "file://${obtain(item, job).absolutePath}"
          }
        }
      }
    }
    job.workers.addAll(workers)
    job.check()
    workers.forEach { it.get() }
    Log.i("ApplicationStore", "prepared app=$appId release=$releaseId assets=${paths.size}")
    return PreparedApplication(checkNotNull(bundle.get()), paths.toMap())
  }

  private fun obtain(manifest: JSONObject, job: Preparation): File {
    val key = manifest.getString("appId") + "/" + manifest.getJSONObject("bundle").getString("sha256")
    val lock = downloadLocks[(key.hashCode() and Int.MAX_VALUE) % downloadLocks.size]
    return synchronized(lock) { job.check(); obtainLocked(manifest, job) }
  }

  private fun obtainLocked(manifest: JSONObject, job: Preparation): File {
    val appId = manifest.getString("appId")
    val releaseId = manifest.getString("releaseId")
    require(Regex("^[a-z][a-z0-9-]{0,63}$").matches(appId)) { "invalid application identity" }
    require(Regex("^[a-f0-9]{64}$").matches(releaseId)) { "invalid application release" }
    val bundle = manifest.getJSONObject("bundle")
    val sha = bundle.getString("sha256")
    val bytes = bundle.getLong("bytes")
    require(Regex("^[a-f0-9]{64}$").matches(sha) && bytes in 1..50_000_000) { "invalid application bundle" }
    val directory = File(root, appId).apply { mkdirs() }
    val file = File(directory, "$sha.bundle")
    if (complete(file, bytes)) return file
    val temporary = File(directory, "$sha.tmp")
    try {
      val aliases = try {
        context.assets.open("applications/$appId/asset-aliases.json").bufferedReader().use { JSONObject(it.readText()) }
      } catch (_: java.io.FileNotFoundException) { JSONObject() }
      val sharedAsset = aliases.optString(sha)
      val seedPath = if (sharedAsset.matches(Regex("^jolly/[a-z0-9_]+\\.mp4$"))) sharedAsset else "applications/$appId/$sha.bundle"
      context.assets.open(seedPath).use { input -> temporary.outputStream().use { input.copyTo(it) } }
      if (complete(temporary, bytes) && temporary.renameTo(file)) return file
    } catch (_: java.io.FileNotFoundException) { /* The application is not included in this APK seed. */ }
    temporary.delete()
    val connection = URL("https://zjh-cdn.297782.xyz/v1/app/$sha").openConnection() as HttpURLConnection
    connection.connectTimeout = 10000
    connection.readTimeout = 15000
    val transfer = Transfer()
    job.connections[connection] = transfer
    try {
      job.check()
      check(connection.responseCode == 200) { "application download failed ${connection.responseCode}" }
      connection.inputStream.use { input ->
        temporary.outputStream().use { output ->
          val buffer = ByteArray(32768)
          var total = 0L
          while (true) {
            job.check()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= bytes) { "application exceeds declared size" }
            output.write(buffer, 0, count)
            transfer.bytes.addAndGet(count.toLong())
          }
        }
      }
      job.check()
      check(complete(temporary, bytes)) { "application download incomplete" }
      check(temporary.renameTo(file)) { "application cache write failed" }
      Log.i("ApplicationStore", "downloaded app=$appId release=$releaseId bytes=$bytes")
      return file
    } finally {
      job.connections.remove(connection)
      connection.disconnect()
      temporary.delete()
    }
  }

  fun seed(appId: String): JSONObject? {
    require(Regex("^[a-z][a-z0-9-]{0,63}$").matches(appId)) { "invalid application identity" }
    return try { context.assets.open("applications/$appId/manifest.json").bufferedReader().use { JSONObject(it.readText()) } }
    catch (_: java.io.FileNotFoundException) { null }
  }

  private fun complete(file: File, bytes: Long): Boolean = file.isFile && file.length() == bytes
}
