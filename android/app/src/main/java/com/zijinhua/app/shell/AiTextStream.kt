package com.zijinhua.app.shell

import org.json.JSONObject
import java.io.Reader

/** Bounded SSE decoder. UTF-8 decoding belongs to the Reader; events may span network chunks. */
internal object AiTextStream {
  fun read(reader: Reader, cancelled: () -> Boolean, emit: (String) -> Unit, responses: Boolean = false): String {
    val text = StringBuilder()
    val line = StringBuilder()
    val data = StringBuilder()
    val pending = StringBuilder()
    var done = false
    var finished = false
    var size = 0
    var lastEmit = System.nanoTime()
    fun flush() { if (pending.isNotEmpty()) { emit(pending.toString()); pending.setLength(0); lastEmit = System.nanoTime() } }
    fun event() {
      if (data.isEmpty()) return
      val value = data.toString(); data.setLength(0)
      if (value == "[DONE]") { require(!responses || finished) { "response interrupted" }; done = true; return }
      val json = JSONObject(value)
      require(!json.has("error")) { "stream error" }
      if (responses) {
        when (json.optString("type")) {
          "response.failed", "response.incomplete", "error" -> error("response failed or incomplete")
          "response.output_text.delta" -> {
            val content = json.opt("delta") as? String ?: error("invalid response delta")
            text.append(content); pending.append(content)
            if (System.nanoTime() - lastEmit >= 40_000_000) flush()
          }
          "response.completed" -> {
            val final = AiResponseText.read(json.getJSONObject("response"))
            // Read through the terminal event; text deltas alone never mean success.
            if (text.isEmpty()) { text.append(final); pending.append(final) }
            finished = true; done = true
          }
        }
        return
      }
      val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
      val reason = choice.optString("finish_reason")
      if (reason.isNotBlank() && reason != "null") {
        require(reason == "stop" || reason == "length") { "unsupported completion" }
        finished = true
      }
      val content = choice.optJSONObject("delta")?.opt("content") as? String ?: return
      text.append(content); pending.append(content)
      if (System.nanoTime() - lastEmit >= 40_000_000) flush()
    }
    fun consumeLine() {
      val value = line.toString().trimEnd('\r'); line.setLength(0)
      if (value.isEmpty()) event()
      else if (value.startsWith("data:")) {
        if (data.isNotEmpty()) data.append('\n')
        data.append(value.substring(5).removePrefix(" "))
      }
    }
    val buffer = CharArray(2048)
    while (!done) {
      if (cancelled()) throw java.io.IOException("cancelled")
      val count = reader.read(buffer)
      if (count < 0) break
      size += count
      require(size <= 4 * 1024 * 1024) { "stream too large" }
      for (index in 0 until count) {
        if (buffer[index] == '\n') consumeLine() else line.append(buffer[index])
        if (done) break
      }
    }
    if (line.isNotEmpty()) consumeLine()
    event()
    require(done || finished) { "stream interrupted" }
    if (cancelled()) throw java.io.IOException("cancelled")
    flush()
    return text.toString()
  }
}
