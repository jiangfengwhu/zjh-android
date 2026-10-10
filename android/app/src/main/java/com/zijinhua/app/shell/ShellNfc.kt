package com.zijinhua.app.shell

import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.util.Log
import com.zijinhua.app.plugin.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object ShellNfc {
  private const val TAG = "ShellNfc"
  private val reading = AtomicBoolean(false)

  fun register(registry: PluginRegistry) {
    registry.register("shell", "nfcStatus") { _ -> status().toString() }
    registry.register("shell", "nfcRead") { payload -> read(JSONObject(payload.ifBlank { "{}" })).toString() }
  }

  private fun status(): JSONObject {
    val adapter = NfcAdapter.getDefaultAdapter(ShellCapabilities.hostContext())
    return JSONObject()
      .put("ok", true)
      .put("present", adapter != null)
      .put("enabled", adapter?.isEnabled == true)
  }

  private fun read(input: JSONObject): JSONObject {
    val activity = ShellCapabilities.hostActivity() ?: return JSONObject().put("ok", false).put("error", "activity")
    val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return JSONObject().put("ok", false).put("error", "unavailable")
    if (!adapter.isEnabled) return JSONObject().put("ok", false).put("error", "disabled")
    val seconds = input.optInt("seconds", 20).coerceIn(3, 30)
    val latch = CountDownLatch(1)
    val closed = AtomicBoolean(false)
    val payload = AtomicReference<JSONObject>()
    val flags = NfcAdapter.FLAG_READER_NFC_A or
      NfcAdapter.FLAG_READER_NFC_B or
      NfcAdapter.FLAG_READER_NFC_F or
      NfcAdapter.FLAG_READER_NFC_V or
      NfcAdapter.FLAG_READER_NFC_BARCODE
    if (!reading.compareAndSet(false, true)) return JSONObject().put("ok", false).put("error", "busy")
    try {
      activity.runOnUiThread {
        if (!closed.get()) try {
          adapter.enableReaderMode(activity, { tag ->
            if (!closed.compareAndSet(false, true)) return@enableReaderMode
            try { payload.set(describe(tag)) } catch (_: Exception) { payload.set(JSONObject().put("ok", false).put("error", "unavailable")) }
            finally { activity.runOnUiThread { runCatching { adapter.disableReaderMode(activity) } }; latch.countDown() }
          }, flags, null)
        } catch (_: Exception) { payload.set(JSONObject().put("ok", false).put("error", "unavailable")); closed.set(true); latch.countDown() }
      }
      if (!latch.await(seconds.toLong(), TimeUnit.SECONDS)) return JSONObject().put("ok", false).put("error", "timeout")
      return payload.get() ?: JSONObject().put("ok", false).put("error", "empty")
    } finally {
      closed.set(true)
      // Release ownership on the same looper as reader-mode changes, before the next read starts.
      activity.runOnUiThread { try { adapter.disableReaderMode(activity) } finally { reading.set(false) } }
    }
  }

  private fun describe(tag: Tag): JSONObject {
    val records = JSONArray()
    val texts = ArrayList<String>()
    val message = android.nfc.tech.Ndef.get(tag)?.cachedNdefMessage
    message?.records?.forEach { record ->
      val text = recordText(record)
      if (text.isNotEmpty()) texts.add(text)
      records.put(JSONObject().put("text", text).put("type", String(record.type, Charsets.US_ASCII)))
    }
    return JSONObject()
      .put("ok", true)
      .put("id", tag.id.joinToString("") { "%02x".format(it) })
      .put("text", texts.joinToString("\n"))
      .put("records", records)
  }

  private fun recordText(record: NdefRecord): String {
    if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
      val bytes = record.payload
      if (bytes.isEmpty()) return ""
      val languageLength = bytes[0].toInt() and 0x3F
      val start = (1 + languageLength).coerceAtMost(bytes.size)
      return String(bytes, start, bytes.size - start, if (bytes[0].toInt() and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8)
    }
    if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI)) {
      return record.toUri().toString()
    }
    return ""
  }
}
