package com.zijinhua.app.shell

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One bounded UTF-8 read per request. The caller controls backpressure. */
object ShellTextFiles {
  fun read(context: Context, input: JSONObject, alive: () -> Boolean): JSONObject {
    val appId = input.getString("appId")
    val root = File(context.filesDir, "applications/$appId").canonicalFile
    val file = File(input.getString("path")).canonicalFile
    require(file.path.startsWith(root.path + File.separator) && file.isFile)
    val offset = input.getLong("offset")
    val chunkBytes = input.optInt("chunkBytes", 64 * 1024)
    require(offset >= 0 && chunkBytes in 4096..262144)
    check(alive())
    return RandomAccessFile(file, "r").use { source ->
      val size = source.length()
      val modified = file.lastModified()
      val inode = android.system.Os.fstat(source.fd).st_ino.toString()
      require(offset <= size)
      if (input.has("size")) check(input.getLong("size") == size && input.getLong("modified") == modified && input.getString("inode") == inode) { "file changed" }
      val limit = input.optLong("maxBytes", Long.MAX_VALUE)
      require(limit > 0)
      if (size > limit) return@use ShellOperations.result("too-large")
      source.seek(offset)
      val bytes = ByteArray(minOf(chunkBytes.toLong(), size - offset).toInt())
      source.readFully(bytes)
      check(alive())
      var count = bytes.size
      // Leave an incomplete final code point for the next request. This also
      // keeps four-byte emoji intact without retaining any native cursor.
      if (offset + count < size && count > 0) {
        var start = count - 1
        while (start > 0 && (bytes[start].toInt() and 0xc0) == 0x80) start--
        val lead = bytes[start].toInt() and 0xff
        val width = when (lead) { in 0xc2..0xdf -> 2; in 0xe0..0xef -> 3; in 0xf0..0xf4 -> 4; else -> 1 }
        if (start + width > count) count = start
      }
      val text = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, 0, count)).toString()
      check(alive() && source.length() == size && file.lastModified() == modified && android.system.Os.stat(file.path).st_ino.toString() == inode) { "file changed" }
      ShellOperations.result().put("text", text).put("nextOffset", offset + count)
        .put("done", offset + count == size).put("size", size).put("modified", modified).put("inode", inode)
    }
  }
}
