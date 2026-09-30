package com.zijinhua.app.bundle

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Immutable application blobs. JS checks the public SDK contract before invoking this loader. */
class ApplicationStore(private val context: Context) {
  private val root = File(context.filesDir, "applications")

  fun obtain(manifest: JSONObject): File {
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
    if (valid(file, sha, bytes)) return file
    val temporary = File(directory, "$sha.tmp")
    try {
      context.assets.open("applications/$appId/$sha.bundle").use { input -> temporary.outputStream().use { input.copyTo(it) } }
      if (valid(temporary, sha, bytes) && temporary.renameTo(file)) return file
    } catch (_: java.io.FileNotFoundException) { /* The application is not included in this APK seed. */ }
    temporary.delete()
    val connection = URL("https://zjh-cdn.297782.xyz/v1/app/$sha").openConnection() as HttpURLConnection
    connection.connectTimeout = 10000
    connection.readTimeout = 60000
    try {
      check(connection.responseCode == 200) { "application download failed ${connection.responseCode}" }
      connection.inputStream.use { input ->
        temporary.outputStream().use { output ->
          val buffer = ByteArray(32768)
          var total = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= bytes) { "application exceeds declared size" }
            output.write(buffer, 0, count)
          }
        }
      }
      check(valid(temporary, sha, bytes)) { "application checksum mismatch" }
      check(temporary.renameTo(file)) { "application cache write failed" }
      Log.i("ApplicationStore", "downloaded app=$appId release=$releaseId bytes=$bytes")
      return file
    } finally {
      connection.disconnect()
      temporary.delete()
    }
  }

  fun seed(appId: String): JSONObject? {
    require(Regex("^[a-z][a-z0-9-]{0,63}$").matches(appId)) { "invalid application identity" }
    return try { context.assets.open("applications/$appId/manifest.json").bufferedReader().use { JSONObject(it.readText()) } }
    catch (_: java.io.FileNotFoundException) { null }
  }

  fun assets(manifest: JSONObject): JSONObject {
    val result = JSONObject()
    val entries = manifest.optJSONArray("assets") ?: return result
    for (index in 0 until entries.length()) {
      val asset = entries.getJSONObject(index)
      val name = asset.getString("name")
      require(name.isNotEmpty() && !name.contains("..") && !name.startsWith("/") && !name.contains("\\")) { "invalid asset name" }
      val item = JSONObject(manifest.toString()).put("bundle", asset)
      result.put(name, "file://${obtain(item).absolutePath}")
    }
    return result
  }

  private fun valid(file: File, sha: String, bytes: Long): Boolean {
    if (!file.isFile || file.length() != bytes) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(32768)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } == sha
  }
}
