package com.zijinhua.app.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** File contents stay on the native IO pool. Mutations commit once, after cancellation checks. */
object ShellAppFiles {
  private val locks = Array(64) { Any() }
  private fun <T> locked(paths: List<File>, block: () -> T): T {
    val indexes = paths.map { (it.path.hashCode() and Int.MAX_VALUE) % locks.size }.distinct().sorted()
    fun acquire(index: Int): T = if (index == indexes.size) block() else synchronized(locks[indexes[index]]) { acquire(index + 1) }
    return acquire(0)
  }
  fun commit(context: Context, input: JSONObject, alive: () -> Boolean): JSONObject {
    val target = file(context, input, "path"); val source = file(context, input, "temporary")
    require(source.parentFile == target.parentFile && source != target && source.isFile)
    return locked(listOf(target)) { check(alive()); android.system.Os.rename(source.path, target.path); ShellOperations.result() }
  }
  private fun file(context: Context, input: JSONObject, key: String): File {
    val root = File(context.filesDir, "applications/${input.getString("appId")}").canonicalFile
    return File(input.getString(key)).canonicalFile.also { require(it.path.startsWith(root.path + File.separator) || it == root) }
  }
  fun call(context: Context, method: String, input: JSONObject, alive: () -> Boolean): JSONObject {
    val source = file(context, input, "path")
    val destination = if (method in setOf("fileCopy", "fileMove")) file(context, input, "target") else source
    return locked(listOf(source, destination)) {
      check(alive())
      when (method) {
        "fileStat" -> {
          val exists = source.exists()
          ShellOperations.result().put("exists", exists).apply {
            if (exists) put("type", if (source.isDirectory) "directory" else "file").put("size", source.length()).put("modifiedMs", source.lastModified())
          }
        }
        "fileListPage" -> {
          if (!source.exists()) return@locked ShellOperations.result().put("items", JSONArray()).put("nextCursor", JSONObject.NULL)
          require(source.isDirectory)
          val limit = input.optInt("limit", 50).also { require(it in 1..200) }
          val after = input.optString("cursor")
          // Keep only one page, rather than materializing every directory entry or stat.
          val candidates = java.util.TreeSet<String>()
          Files.newDirectoryStream(source.toPath()).use { entries -> entries.forEach { entry ->
            check(alive())
            val name = entry.fileName.toString()
            if (name > after && !(source == File(context.filesDir, "applications/${input.getString("appId")}").canonicalFile && input.optBoolean("device") && (name == "accounts" || name.matches(Regex("[a-f0-9]{64}\\.(bundle|tmp)"))))) {
              candidates.add(name); if (candidates.size > limit + 1) candidates.pollLast()
            }
          } }
          val page = candidates.take(limit)
          ShellOperations.result().put("items", JSONArray(page)).put("nextCursor", if (candidates.size > limit) page.last() else JSONObject.NULL)
        }
        "fileCopy", "fileMove", "fileAppendText" -> {
          val target = if (method == "fileAppendText") source else file(context, input, "target")
          require(target != File(context.filesDir, "applications/${input.getString("appId")}").canonicalFile)
          require(source != target || method == "fileAppendText")
          val text = if (method == "fileAppendText") input.getString("text").toByteArray(Charsets.UTF_8).also { if (it.size > 6 * 1024 * 1024) throw IOException("too-large") } else null
          if (source.exists()) require(source.isFile)
          else if (method != "fileAppendText") return@locked ShellOperations.result("not-found")
          val overwrite = input.optBoolean("overwrite", false)
          if (method != "fileAppendText" && !overwrite && target.exists()) return@locked ShellOperations.result("exists")
          require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
          if (method == "fileMove") {
            check(alive())
            if (overwrite) Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) else Files.move(source.toPath(), target.toPath())
            return@locked ShellOperations.result()
          }
          val tmp = File(target.parentFile, ".${target.name}.operation-${UUID.randomUUID()}")
          try {
            tmp.outputStream().use { out ->
              if (method == "fileCopy" || source.exists()) source.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { check(alive()); val count = stream.read(buffer); if (count < 0) break; out.write(buffer, 0, count) }
              }
              check(alive()); if (text != null) out.write(text)
            }
            check(alive())
            val flags = if (overwrite || method == "fileAppendText") arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
            Files.move(tmp.toPath(), target.toPath(), *flags)
            ShellOperations.result()
          } finally { tmp.delete() }
        }
        else -> ShellOperations.result("unsupported")
      }
    }
  }
}
