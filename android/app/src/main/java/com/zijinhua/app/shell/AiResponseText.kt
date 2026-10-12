package com.zijinhua.app.shell

import org.json.JSONObject

/** Responses has ordered output items, not Chat Completions' choices envelope. */
internal object AiResponseText {
  fun read(response: JSONObject): String {
    require(response.optString("status") == "completed" && response.isNull("error")) { "response failed or incomplete" }
    val text = StringBuilder()
    val items = response.optJSONArray("output") ?: return ""
    for (index in 0 until items.length()) {
      val item = items.optJSONObject(index) ?: continue
      if (item.optString("type") != "message" || item.optString("role") != "assistant") continue
      val parts = item.optJSONArray("content") ?: continue
      for (partIndex in 0 until parts.length()) {
        val part = parts.optJSONObject(partIndex) ?: continue
        if (part.optString("type") == "output_text") text.append(part.optString("text"))
      }
    }
    return text.toString()
  }
}
