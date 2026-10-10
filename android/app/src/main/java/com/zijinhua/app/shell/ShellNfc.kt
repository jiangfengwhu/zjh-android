package com.zijinhua.app.shell

import android.content.Context
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object ShellNfc {
  private val main = Handler(Looper.getMainLooper())
  private var active: Any? = null
  private var reading = false // main looper
  fun status(context: Context): JSONObject {
    val adapter = NfcAdapter.getDefaultAdapter(context)
    return ShellOperations.result().put("present", adapter != null).put("enabled", adapter?.isEnabled == true)
  }
  suspend fun read(context: Context, input: JSONObject): JSONObject {
    if (reading) return ShellOperations.result("busy")
    val host = ShellCapabilities.hostActivity() ?: return ShellOperations.result("unavailable")
    val adapter = NfcAdapter.getDefaultAdapter(context) ?: return ShellOperations.result("unsupported")
    if (!adapter.isEnabled) return ShellOperations.result("disabled")
    val session = Any(); active = session
    reading = true
    try {
      return suspendCancellableCoroutine { continuation ->
        val closed = AtomicBoolean(false)
        fun close() { if (active === session) runCatching { adapter.disableReaderMode(host) } }
        continuation.invokeOnCancellation { closed.set(true); main.post { close() } }
        try {
          adapter.enableReaderMode(host, { tag ->
            if (!closed.compareAndSet(false, true)) return@enableReaderMode
            val value = try { describe(tag) } catch (_: Exception) { ShellOperations.result("unavailable") }
            main.post { close(); if (continuation.isActive) continuation.resume(value) }
          }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
        } catch (_: Exception) { closed.set(true); close(); if (continuation.isActive) continuation.resume(ShellOperations.result("unavailable")) }
      }
    } finally { runCatching { adapter.disableReaderMode(host) }; if (active === session) active = null; reading = false }
  }
  private fun describe(tag: Tag): JSONObject {
    val records = JSONArray(); val texts = mutableListOf<String>()
    val message = android.nfc.tech.Ndef.get(tag)?.cachedNdefMessage
    require((message?.byteArrayLength ?: 0) <= 65536)
    message?.records?.take(64)?.forEach { record ->
      val text = when {
        record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
          val bytes = record.payload
          if (bytes.isEmpty()) "" else {
            val start = 1 + (bytes[0].toInt() and 0x3F); require(start <= bytes.size)
            String(bytes, start, bytes.size - start, if (bytes[0].toInt() and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8)
          }
        }
        record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI) -> record.toUri()?.toString() ?: ""
        else -> ""
      }
      if (text.isNotEmpty()) texts.add(text)
      records.put(JSONObject().put("text", text).put("type", String(record.type, Charsets.US_ASCII)))
    }
    return ShellOperations.result().put("id", tag.id.joinToString("") { "%02x".format(it) }).put("text", texts.joinToString("\n"))
      .put("records", records).put("technologies", JSONArray(tag.techList.toList()))
  }
}
