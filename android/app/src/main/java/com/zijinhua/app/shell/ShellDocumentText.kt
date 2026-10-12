package com.zijinhua.app.shell

import android.content.Context
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.Writer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/** All parsing stays on the document IO pool; only bounded text crosses the bridge. */
internal object ShellDocumentText {
  class Failure(val reason: String) : RuntimeException(reason)
  private class Full : RuntimeException()
  private class Text(private val limit: Int, private val cancelled: AtomicBoolean) : Writer() {
    val value = StringBuilder()
    var truncated = false
    fun check() { if (cancelled.get()) throw Failure("cancel") }
    override fun write(buffer: CharArray, offset: Int, length: Int) {
      check()
      val count = minOf(length, limit - value.length)
      value.append(buffer, offset, count)
      if (count < length) { truncated = true; throw Full() }
    }
    override fun flush() { check() }
    override fun close() {}
    fun result(): String {
      // Do not return half of a surrogate pair when the character limit is reached.
      if (value.isNotEmpty() && Character.isHighSurrogate(value.last())) value.setLength(value.length - 1)
      return value.toString().trim()
    }
  }
  private fun checked(source: InputStream, cancelled: AtomicBoolean, limit: Long = Long.MAX_VALUE): InputStream = object : FilterInputStream(source) {
    var size = 0L
    private fun accept(count: Int) {
      if (cancelled.get()) throw Failure("cancel")
      if (count > 0) { size += count; if (size > limit) throw Failure("too-large") }
    }
    override fun read(): Int { accept(0); return super.read().also { accept(if (it < 0) 0 else 1) } }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { accept(0); return `in`.read(buffer, offset, length).also { accept(it) } }
  }
  fun extract(context: Context, source: File, maxChars: Int, cancelled: AtomicBoolean): JSONObject {
    require(maxChars in 1..128000)
    if (source.length() > 20L * 1024 * 1024) throw Failure("too-large")
    val header = ByteArray(8)
    source.inputStream().use { it.read(header) }
    val format = when {
      header.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray()) -> "pdf"
      header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() -> "docx"
      header.contentEquals(byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())) -> "doc"
      else -> throw Failure("document-format")
    }
    val text = Text(maxChars, cancelled)
    var pages: Int? = null
    try {
      when (format) {
        "pdf" -> {
          PDFBoxResourceLoader.init(context.applicationContext)
          PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { doc ->
            pages = doc.numberOfPages
            if (doc.isEncrypted && !doc.currentAccessPermission.canExtractContent()) throw Failure("encrypted-document")
            if (doc.numberOfPages > 200) throw Failure("too-large")
            object : PDFTextStripper() {
              override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) { text.check(); super.startPage(page) }
            }.apply { sortByPosition = true }.writeText(doc, text)
          }
        }
        "docx" -> docx(source, text, cancelled)
        "doc" -> checked(source.inputStream(), cancelled).use { input ->
          POIFSFileSystem(input).use { fs ->
            if (fs.root.hasEntry("EncryptedPackage")) throw Failure("encrypted-document")
            HWPFDocument(fs).use { doc ->
              val range = doc.range
              for (index in 0 until range.numParagraphs()) {
                text.check()
                text.write(WordExtractor.stripFields(range.getParagraph(index).text()).replace('\u0007', '\t').replace('\r', '\n'))
              }
            }
          }
        }
      }
    } catch (_: Full) { /* Partial text is explicit, never silently complete. */ }
    catch (_: InvalidPasswordException) { throw Failure("encrypted-document") }
    catch (_: EncryptedDocumentException) { throw Failure("encrypted-document") }
    text.check()
    return JSONObject().put("ok", true).put("text", text.result()).put("format", format).put("truncated", text.truncated)
      .apply { pages?.let { put("pages", it) } }
  }
  private fun docx(source: File, text: Text, cancelled: AtomicBoolean) {
    ZipFile(source).use { zip ->
      val entry = zip.getEntry("word/document.xml") ?: throw Failure("document-format")
      if (entry.size > 16L * 1024 * 1024) throw Failure("too-large")
      checked(zip.getInputStream(entry), cancelled, 16L * 1024 * 1024).use { input ->
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        var inText = false
        var deleted = 0
        val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val strict = "http://purl.oclc.org/ooxml/wordprocessingml/main"
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
          text.check()
          when (parser.eventType) {
            XmlPullParser.DOCDECL -> throw Failure("invalid-document")
            XmlPullParser.START_TAG -> if (parser.namespace == word || parser.namespace == strict) {
              when (parser.name) {
                "del" -> deleted++
                "t" -> inText = deleted == 0
                "tab" -> if (deleted == 0) text.write("\t")
                "br", "cr" -> if (deleted == 0) text.write("\n")
              }
            }
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inText) text.write(parser.text)
            XmlPullParser.END_TAG -> if (parser.namespace == word || parser.namespace == strict) {
              when (parser.name) {
                "del" -> deleted--
                "t" -> inText = false
                "p" -> if (deleted == 0) text.write("\n")
                "tc" -> if (deleted == 0) text.write("\t")
              }
            }
          }
          parser.nextToken()
        }
      }
    }
  }
}
