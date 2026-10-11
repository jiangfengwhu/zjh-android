package com.zijinhua.app.bundle

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** 当前运行的 vendor 和 app 包各自的大小。 */
class BundleInventory(private val context: Context) {
  fun toJson(): String {
    val items = JSONArray()
    val active = ReleaseStore(context).readActive()
    if (active != null) {
      add(items, "vendor", active.optJSONObject("vendor"))
      add(items, "app", active.optJSONObject("app"))
    }
    Log.i(TAG, "packages ${describe(items)}")
    return JSONObject().put("ok", true).put("items", items).toString()
  }

  private fun add(items: JSONArray, id: String, entry: JSONObject?) {
    val bytes = entry?.optLong("bytes") ?: 0L
    items.put(
      JSONObject()
        .put("id", id)
        .put("kind", "layer")
        .put("bytes", bytes)
        .put("present", bytes > 0L),
    )
  }

  private fun describe(items: JSONArray): String {
    val parts = ArrayList<String>(items.length())
    for (index in 0 until items.length()) {
      val item = items.getJSONObject(index)
      parts += "${item.getString("kind")}:${item.getString("id")}=${item.getLong("bytes")}"
    }
    return parts.joinToString(" ")
  }

  private companion object {
    const val TAG = "BundleInventory"
  }
}
