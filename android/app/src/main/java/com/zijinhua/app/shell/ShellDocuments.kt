package com.zijinhua.app.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PageRange
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** Offline OCR and printing of existing PDF files. */
object ShellDocuments {
  val methods = setOf("documentRecognize", "documentPrint", "documentCancel", "documentStopApp")
  private val main = Handler(Looper.getMainLooper())
  private val io = Executors.newFixedThreadPool(2)
  private val tasks = mutableMapOf<String, () -> Unit>() // main looper only
  private val owners = mutableMapOf<String, android.app.Activity>()
  fun stopAll() { main.post { tasks.values.toList().forEach { it() } } }
  fun stopActivity(activity: android.app.Activity) { main.post {
    owners.filterValues { it === activity }.keys.toList().forEach { tasks[it]?.invoke() }
  } }
  private fun stopApp(appId: String) { tasks.filterKeys { it.startsWith("$appId:") }.values.toList().forEach { it() } }
  private fun result(error: String? = null) = JSONObject().put("ok", error == null).apply { if (error != null) put("error", error) }
  private fun file(context: Context, input: JSONObject, exists: Boolean = true): File {
    val appId = input.getString("appId").also { require(it.matches(Regex("[a-z][a-z0-9-]{0,63}"))) }
    val root = File(context.filesDir, "applications/$appId").canonicalFile
    return File(input.getString("path")).canonicalFile.also {
      require(it.path.startsWith(root.path + File.separator) && (!exists || it.isFile))
    }
  }
  fun call(context: Context, method: String, input: JSONObject, reply: (JSONObject) -> Unit, isCurrent: () -> Boolean = { true }) {
    main.post {
      if (!isCurrent()) return@post
      try {
        if (method == "documentStopApp") { stopApp(input.getString("appId")); reply(result()); return@post }
        val key = "${input.getString("appId")}:${input.getString("requestId")}".also { require(it.length <= 225) }
        if (method == "documentCancel") { tasks[key]?.invoke(); reply(result()); return@post }
        if (method == "documentPrint" && !ShellVisibility.active(input.getString("appId"))) { reply(result("cancel")); return@post }
        if (tasks.size >= 3 || tasks.containsKey(key)) { reply(result("busy")); return@post }
        when (method) {
          "documentRecognize" -> recognize(context, input, key, reply)
          "documentPrint" -> print(context, input, key, reply)
        }
      } catch (_: Exception) { reply(result("unavailable")) }
    }
  }
  private fun recognize(context: Context, input: JSONObject, key: String, reply: (JSONObject) -> Unit) {
    val source = file(context, input)
    require(source.length() <= 20 * 1024 * 1024)
    val cancelled = AtomicBoolean(false)
    var replied = false
    lateinit var timeout: Runnable
    fun respond(value: JSONObject) {
      if (replied) return
      replied = true; main.removeCallbacks(timeout); reply(value)
    }
    // A cancelled ML Kit call still occupies its physical slot until completion.
    fun release() { tasks.remove(key); main.removeCallbacks(timeout) }
    fun cancel(error: String) { cancelled.set(true); respond(result(error)) }
    timeout = Runnable { cancel("timeout") }
    tasks[key] = { cancel("cancel") }; main.postDelayed(timeout, 30000)
    io.execute {
      var decoded: Bitmap? = null
      try {
        if (cancelled.get()) { main.post { release() }; return@execute }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 4_000_000) sample *= 2
        val original = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        decoded = original
        val orientation = runCatching { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply {
          when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }
            8 -> setRotate(270f)
          }
        }
        val bitmap = if (matrix.isIdentity) original else Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        decoded = bitmap
        if (bitmap !== original) original.recycle()
        main.post {
          if (cancelled.get()) { bitmap.recycle(); release(); return@post }
          try {
            MlKit.initialize(context.applicationContext)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
              recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                try {
                  if (!replied) {
                    if (!task.isSuccessful) respond(result("unavailable"))
                    else {
                      val text = task.result
                      val blocks = JSONArray()
                      text.textBlocks.forEach { block ->
                        blocks.put(JSONObject().put("text", block.text).put("lines", JSONArray(block.lines.map { it.text }))
                          .apply { block.boundingBox?.let { put("bounds", JSONObject().put("x", it.left).put("y", it.top).put("width", it.width()).put("height", it.height())) } })
                      }
                      respond(result().put("text", text.text).put("blocks", blocks).put("width", bitmap.width).put("height", bitmap.height))
                    }
                  }
                } finally { recognizer.close(); bitmap.recycle(); release() }
              }
            } catch (error: Exception) { recognizer.close(); throw error }
          } catch (_: Exception) { bitmap.recycle(); respond(result("unavailable")); release() }
        }
      } catch (_: Exception) { decoded?.recycle(); main.post { respond(result("file")); release() } }
    }
  }
  private fun print(context: Context, input: JSONObject, key: String, reply: (JSONObject) -> Unit) {
    val source = file(context, input)
    val host = ShellCapabilities.hostActivity() ?: run { reply(result("unavailable")); return }
    val manager = host.getSystemService(PrintManager::class.java) ?: run { reply(result("unsupported")); return }
    val title = input.optString("title", source.name).take(100)
    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    val lock = Any()
    var snapshot: File? = null
    var count = 0
    var job: android.print.PrintJob? = null
    fun cleanup() {
      finished.set(true); tasks.remove(key); owners.remove(key)
      io.execute { synchronized(lock) { snapshot?.delete(); snapshot = null } }
    }
    tasks[key] = { job?.cancel(); cleanup() }; owners[key] = host
    try {
      job = manager.print(title, object : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: android.os.Bundle?) {
          io.execute {
            try {
              if (cancel.isCanceled || finished.get()) { callback.onLayoutCancelled(); return@execute }
              val ready = synchronized(lock) {
                snapshot ?: run {
                  require(source.length() in 1..(50L * 1024 * 1024))
                  val copy = File.createTempFile("print-", ".pdf", context.cacheDir)
                  try { source.copyTo(copy, overwrite = true); snapshot = copy; copy }
                  catch (error: Exception) { copy.delete(); throw error }
                }
              }
              count = android.graphics.pdf.PdfRenderer(ParcelFileDescriptor.open(ready, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
              if (cancel.isCanceled || finished.get()) callback.onLayoutCancelled()
              else callback.onLayoutFinished(PrintDocumentInfo.Builder(title).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(count).build(), old != new)
            } catch (_: Exception) { callback.onLayoutFailed("无法读取 PDF") }
          }
        }
        override fun onWrite(pages: Array<PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
          io.execute {
            try {
              val ready = synchronized(lock) { requireNotNull(snapshot) }
              val selected = (0 until count).filter { index -> pages.any { index >= it.start && index <= it.end } }
              require(selected.isNotEmpty())
              val ranges = mutableListOf<PageRange>()
              selected.forEach { index ->
                val previous = ranges.lastOrNull()
                if (previous != null && previous.end + 1 == index) ranges[ranges.lastIndex] = PageRange(previous.start, index)
                else ranges.add(PageRange(index, index))
              }
              java.io.FileOutputStream(destination.fileDescriptor).use { out ->
                val checked = object : java.io.FilterOutputStream(out) {
                  override fun write(value: Int) { check(!cancel.isCanceled && !finished.get()); out.write(value) }
                  override fun write(buffer: ByteArray, offset: Int, length: Int) { check(!cancel.isCanceled && !finished.get()); out.write(buffer, offset, length) }
                }
                if (selected.size == count) ready.inputStream().use { it.copyTo(checked, 64 * 1024) }
                else {
                  PDFBoxResourceLoader.init(context.applicationContext)
                  fun memory() = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
                  PDDocument.load(ready, memory()).use { original ->
                    PDDocument(memory()).use { output ->
                      selected.forEach { index ->
                        check(!cancel.isCanceled && !finished.get())
                        val page = original.getPage(index)
                        output.importPage(page).resources = page.resources
                      }
                      output.save(checked)
                    }
                  }
                }
              }
              if (cancel.isCanceled || finished.get()) callback.onWriteCancelled() else callback.onWriteFinished(ranges.toTypedArray())
            } catch (_: Exception) { if (cancel.isCanceled || finished.get()) callback.onWriteCancelled() else callback.onWriteFailed("无法读取 PDF") }
          }
        }
        override fun onFinish() {
          cleanup()
        }
      }, null)
      // Open first; file snapshot/validation runs during asynchronous layout, never before the panel.
      reply(result().put("opened", true))
    } catch (_: Exception) { cleanup(); reply(result("unavailable")) }
  }
}
