package com.zijinhua.app.shell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ShellFileTransfers {
  private class TooLarge : java.io.IOException()
  private val io = Executors.newFixedThreadPool(2)
  private val closing = Executors.newSingleThreadExecutor()
  private var host: ComponentActivity? = null
  private var picker: ActivityResultLauncher<Array<String>>? = null
  private var exporter: ActivityResultLauncher<Intent>? = null
  private var pending: CancellableContinuation<Uri?>? = null
  fun attach(activity: ComponentActivity) {
    host = activity
    picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> finish(uri) }
    exporter = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { value -> finish(if (value.resultCode == Activity.RESULT_OK) value.data?.data else null, true) }
  }
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private fun finish(uri: Uri?, exported: Boolean = false) {
    val waiter = pending; pending = null
    if (waiter?.isActive == true) waiter.resume(uri) {
      if (exported && uri != null) deleteExport(uri)
    }
    else if (exported && uri != null) deleteExport(uri)
  }
  private fun deleteExport(uri: Uri) {
    val context = host?.applicationContext ?: return
    closing.execute { runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } }
  }
  fun onResume(activity: ComponentActivity) {
    if (host === activity && pending?.isActive == false) pending = null
  }
  fun detach(activity: ComponentActivity) {
    if (host !== activity) return
    pending?.cancel(); pending = null; picker = null; exporter = null; host = null
  }
  private suspend fun choose(launch: () -> Unit): Uri? = suspendCancellableCoroutine { continuation ->
    check(pending == null)
    pending = continuation
    // Cancellation stops file IO but keeps this physical dialog reserved until its callback.
    try { launch() } catch (error: Exception) { pending = null; continuation.resumeWithException(error) }
  }
  private fun owned(context: Context, input: JSONObject, exists: Boolean): File {
    val root = File(context.filesDir, "applications/${input.getString("appId")}").canonicalFile
    return File(input.getString("path")).canonicalFile.also { require(it.path.startsWith(root.path + File.separator) && (!exists || it.isFile)) }
  }
  private fun limit(input: JSONObject) = input.optLong("maxBytes", 50L * 1024 * 1024).also { require(it in 1..100L * 1024 * 1024) }
  suspend fun importFile(context: Context, input: JSONObject): JSONObject {
    if (pending != null) return ShellOperations.result("busy")
    val launch = picker ?: return ShellOperations.result("unavailable")
    val target = owned(context, input, false)
    val maximum = limit(input)
    val mime = input.optString("mime", "*/*").ifBlank { "*/*" }
    val uri = choose { launch.launch(arrayOf(mime)) } ?: return ShellOperations.result("cancel")
    return copy(context, uri, target, maximum, true)
  }
  suspend fun export(context: Context, input: JSONObject): JSONObject {
    if (pending != null) return ShellOperations.result("busy")
    val launch = exporter ?: return ShellOperations.result("unavailable")
    val source = owned(context, input, true)
    val maximum = limit(input)
    if (source.length() > maximum) return ShellOperations.result("too-large")
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
      .setType(input.optString("mime", "application/octet-stream")).putExtra(Intent.EXTRA_TITLE, input.optString("name", source.name))
    val uri = choose { launch.launch(intent) } ?: return ShellOperations.result("cancel")
    return copy(context, uri, source, maximum, false)
  }
  fun share(context: Context, input: JSONObject): JSONObject {
    val source = owned(context, input, true)
    val activity = host ?: return ShellOperations.result("unavailable")
    val intent = Intent(Intent.ACTION_SEND).setType(input.optString("mime", "application/octet-stream"))
      .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source))
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_SUBJECT, input.optString("title"))
    activity.startActivity(Intent.createChooser(intent, input.optString("title", "分享")))
    return ShellOperations.result()
  }
  private suspend fun copy(context: Context, uri: Uri, file: File, maximum: Long, importing: Boolean): JSONObject = suspendCancellableCoroutine { continuation ->
    val cancelled = AtomicBoolean(false)
    val signal = android.os.CancellationSignal()
    val reader = AtomicReference<Closeable?>(); val writer = AtomicReference<Closeable?>()
    var temporary: File? = null
    val future = io.submit {
      var succeeded = false
      try {
        check(!cancelled.get())
        val resolver = context.contentResolver
        var name = file.name
        if (importing) resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)?.use { cursor ->
          if (cursor.moveToFirst()) {
            val size = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (size >= 0 && !cursor.isNull(size)) if (cursor.getLong(size) > maximum) throw TooLarge()
            val label = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (label >= 0) name = cursor.getString(label) ?: name
          }
        }
        if (importing) {
          require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
          temporary = File(file.parentFile, ".${file.name}.import-${java.util.UUID.randomUUID()}")
        }
        val src: InputStream = if (importing) requireNotNull(resolver.openAssetFileDescriptor(uri, "r", signal)).createInputStream() else file.inputStream()
        reader.set(src)
        src.use {
          check(!cancelled.get())
          val dst: OutputStream = if (importing) temporary!!.outputStream() else android.os.ParcelFileDescriptor.AutoCloseOutputStream(requireNotNull(resolver.openFileDescriptor(uri, "wt", signal)))
          writer.set(dst)
          dst.use { output ->
            var total = 0L; val buffer = ByteArray(64 * 1024)
            while (true) {
              check(!cancelled.get()); val count = src.read(buffer); if (count < 0) break
              total += count; if (total > maximum) throw TooLarge(); output.write(buffer, 0, count)
            }
          }
        }
        check(!cancelled.get())
        if (importing) Files.move(temporary!!.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        succeeded = true
        if (continuation.isActive) continuation.resume(ShellOperations.result().put("name", name))
      } catch (_: TooLarge) { if (continuation.isActive) continuation.resume(ShellOperations.result("too-large")) }
        catch (_: Exception) { if (continuation.isActive) continuation.resume(ShellOperations.result(if (cancelled.get()) "cancel" else "file")) }
      finally {
        reader.set(null); writer.set(null); temporary?.delete()
        if (!importing && !succeeded) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
      }
    }
    continuation.invokeOnCancellation {
      cancelled.set(true); future.cancel(true)
      (io as java.util.concurrent.ThreadPoolExecutor).remove(future as Runnable)
      closing.execute { signal.cancel(); runCatching { reader.get()?.close() }; runCatching { writer.get()?.close() }; if (!importing) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } }
    }
  }
}
