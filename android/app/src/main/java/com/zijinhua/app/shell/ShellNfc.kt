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
    activity.runOnUiThread {
      adapter.enableReaderMode(activity, { tag ->
        if (!closed.compareAndSet(false, true)) return@enableReaderMode
        payload.set(describe(tag))
        adapter.disableReaderMode(activity)
        latch.countDown()
      }, flags, null)
    }
    val got = latch.await(seconds.toLong(), TimeUnit.SECONDS)
    if (closed.compareAndSet(false, true)) {
      activity.runOnUiThread { adapter.disableReaderMode(activity) }
    }
    if (!got) return JSONObject().put("ok", false).put("error", "timeout")
    Log.i(TAG, "nfc tag read")
    return payload.get() ?: JSONObject().put("ok", false).put("error", "empty")
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
      return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }
    if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI)) {
      return record.toUri().toString()
    }
    return ""
  }
}
