package com.zijinhua.app.shell

import android.content.Context
import android.util.Log
import com.zijinhua.app.plugin.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object ShellAi {
  private const val TAG = "ShellAi"
  private const val PREFS = "zijinhua.shell.user"
  private const val KEY_MODE = "appearance"
  private const val KEY_BARS = "systemBars"
  private const val KEY_PROVIDERS = "providers"
  var onSurfaceChanged: (() -> Unit)? = null
  var lightSurface: Boolean = false
    private set
  var statusBarColor: Int? = null
    private set
  var navigationBarColor: Int? = null
    private set
  var windowColor: Int? = null
    private set

  fun register(registry: PluginRegistry, context: Context) {
    val app = context.applicationContext
    restore(app)
    registry.register("shell", "appearance") { payload -> appearance(app, JSONObject(payload.ifBlank { "{}" })).toString() }
    registry.register("shell", "systemBars") { payload -> systemBars(app, JSONObject(payload.ifBlank { "{}" })).toString() }
    registry.register("shell", "providers") { _ -> providers(app).toString() }
    registry.register("shell", "saveProvider") { payload -> saveProvider(app, JSONObject(payload)).toString() }
    registry.register("shell", "deleteProvider") { payload -> deleteProvider(app, JSONObject(payload)).toString() }
    registry.register("shell", "complete") { payload -> complete(app, JSONObject(payload)).toString() }
  }

  fun restore(context: Context) {
    lightSurface = readMode(context) == "light"
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BARS, null) ?: return
    val saved = JSONObject(raw)
    statusBarColor = colorOrNull(saved.optString("statusBar"))
    navigationBarColor = colorOrNull(saved.optString("navigationBar"))
    windowColor = colorOrNull(saved.optString("window"))
  }

  private fun systemBars(context: Context, input: JSONObject): JSONObject {
    val status = colorOrNull(input.optString("statusBar"))
    val navigation = colorOrNull(input.optString("navigationBar")) ?: status
    val window = colorOrNull(input.optString("window")) ?: status
    if (status == null || navigation == null || window == null) {
      return JSONObject().put("ok", false).put("error", "statusBar 需要 #RRGGBB")
    }
    if (input.has("light")) lightSurface = input.optBoolean("light")
    statusBarColor = status
    navigationBarColor = navigation
    windowColor = window
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(
      KEY_BARS,
      JSONObject()
        .put("statusBar", input.optString("statusBar"))
        .put("navigationBar", input.optString("navigationBar"))
        .put("window", input.optString("window"))
        .toString(),
    ).apply()
    Log.i(TAG, "system bars status=${input.optString("statusBar")} light=$lightSurface")
    onSurfaceChanged?.invoke()
    return JSONObject().put("ok", true)
  }

  private fun colorOrNull(value: String): Int? {
    if (!value.startsWith("#")) return null
    return try {
      android.graphics.Color.parseColor(value)
    } catch (_: IllegalArgumentException) {
      null
    }
  }
  fun readMode(context: Context): String {
    val mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "dark")
    return if (mode == "light") "light" else "dark"
  }

  private fun appearance(context: Context, input: JSONObject): JSONObject {
    val next = input.optString("mode")
    if (next == "light" || next == "dark") {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, next).apply()
      lightSurface = next == "light"
      Log.i(TAG, "appearance $next")
      onSurfaceChanged?.invoke()
    }
    return JSONObject().put("ok", true).put("mode", readMode(context))
  }

  private fun providers(context: Context): JSONObject {
    return JSONObject().put("ok", true).put("items", readProviders(context))
  }

  private fun saveProvider(context: Context, input: JSONObject): JSONObject {
    val items = readProviders(context)
    val id = input.optString("id").ifBlank { UUID.randomUUID().toString() }
    val row = JSONObject()
      .put("id", id)
      .put("name", input.optString("name").ifBlank { "未命名" })
      .put("baseUrl", input.optString("baseUrl").trim())
      .put("apiKey", input.optString("apiKey"))
      .put("model", input.optString("model").trim())
      .put("isDefault", input.optBoolean("isDefault"))
    if (row.getString("baseUrl").isBlank() || row.getString("model").isBlank()) {
      return JSONObject().put("ok", false).put("error", "地址和模型不能为空")
    }
    val next = JSONArray()
    var replaced = false
    for (index in 0 until items.length()) {
      val item = items.getJSONObject(index)
      if (item.getString("id") == id) {
        if (row.getString("apiKey").isBlank()) row.put("apiKey", item.optString("apiKey"))
        next.put(row)
        replaced = true
      } else {
        if (row.optBoolean("isDefault")) item.put("isDefault", false)
        next.put(item)
      }
    }
    if (!replaced) next.put(row)
    if (next.length() == 1) next.getJSONObject(0).put("isDefault", true)
    writeProviders(context, next)
    Log.i(TAG, "provider saved id=$id")
    return JSONObject().put("ok", true).put("id", id)
  }

  private fun deleteProvider(context: Context, input: JSONObject): JSONObject {
    val id = input.optString("id")
    val items = readProviders(context)
    val next = JSONArray()
    for (index in 0 until items.length()) {
      val item = items.getJSONObject(index)
      if (item.getString("id") != id) next.put(item)
    }
    if (next.length() > 0 && (0 until next.length()).none { next.getJSONObject(it).optBoolean("isDefault") }) {
      next.getJSONObject(0).put("isDefault", true)
    }
    writeProviders(context, next)
    Log.i(TAG, "provider deleted id=$id")
    return JSONObject().put("ok", true)
  }

  private fun complete(context: Context, input: JSONObject): JSONObject {
    val items = readProviders(context)
    val wanted = input.optString("providerId")
    var provider: JSONObject? = null
    for (index in 0 until items.length()) {
      val item = items.getJSONObject(index)
      if (wanted.isNotBlank() && item.getString("id") == wanted) provider = item
      if (provider == null && item.optBoolean("isDefault")) provider = item
    }
    if (provider == null && items.length() > 0) provider = items.getJSONObject(0)
    val chosen = provider ?: return JSONObject().put("ok", false).put("error", "还没有配置大模型")
    val model = input.optString("model").ifBlank { chosen.optString("model") }
    val endpoint = endpoint(chosen.getString("baseUrl"))
    val body = JSONObject().put("model", model).put("messages", input.optJSONArray("messages") ?: JSONArray())
    Log.i(TAG, "complete provider=${chosen.optString("name")} model=$model")
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 20000
      readTimeout = 120000
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Authorization", "Bearer ${chosen.optString("apiKey")}")
    }
    return try {
      connection.outputStream.use { it.write(body.toString().toByteArray()) }
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
      if (code !in 200..299) {
        JSONObject().put("ok", false).put("status", code).put("error", text.take(500))
      } else {
        val content = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
        JSONObject().put("ok", true).put("text", content).put("providerId", chosen.getString("id")).put("model", model)
      }
    } catch (error: Exception) {
      Log.w(TAG, "complete failed ${error.message}")
      JSONObject().put("ok", false).put("error", error.message ?: "请求失败")
    } finally {
      connection.disconnect()
    }
  }

  private fun endpoint(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
  }

  private fun readProviders(context: Context): JSONArray {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROVIDERS, "[]") ?: "[]"
    return try {
      JSONArray(raw)
    } catch (_: Exception) {
      JSONArray()
    }
  }

  private fun writeProviders(context: Context, items: JSONArray) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROVIDERS, items.toString()).apply()
  }
}
