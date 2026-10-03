package com.zijinhua.app.shell

import android.content.Context
import android.util.Log
import com.zijinhua.app.plugin.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.io.IOException
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
  var immersive: Boolean = false
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
    immersive = false
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
    // Legacy immersive controls status visibility only; navigation is globally hidden.
    if (input.has("immersive")) immersive = input.optBoolean("immersive")
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
    val items = readProviders(context)
    val visible = JSONArray()
    for (index in 0 until items.length()) {
      val item = items.getJSONObject(index)
      visible.put(JSONObject().put("id", item.optString("id")).put("name", item.optString("name"))
        .put("baseUrl", item.optString("baseUrl")).put("model", item.optString("model")).put("isDefault", item.optBoolean("isDefault")))
    }
    return JSONObject().put("ok", true).put("items", visible)
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
    if (!secureEndpoint(row.getString("baseUrl"))) return JSONObject().put("ok", false).put("error", "模型地址必须使用 HTTPS，且不能在地址中填写密钥")
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
      if (wanted.isNotBlank()) { if (item.getString("id") == wanted) provider = item }
      else if (item.optBoolean("isDefault")) provider = item
    }
    if (wanted.isNotBlank() && provider == null) return failure("PROVIDER_NOT_FOUND", "选定的 AI 引擎已移除，请重新选择")
    if (provider == null && items.length() > 0) provider = items.getJSONObject(0)
    val chosen = provider ?: return failure("NOT_CONFIGURED", "请先在我的页面配置 AI 引擎")
    val model = input.optString("model").ifBlank { chosen.optString("model") }
    if (!secureEndpoint(chosen.optString("baseUrl"))) return failure("INVALID_REQUEST", "请将 AI 引擎地址改为 HTTPS，且不要在地址中填写密钥")
    val messages = input.optJSONArray("messages") ?: return failure("INVALID_REQUEST", "请提供对话消息")
    if (messages.length() !in 1..128) return failure("INVALID_REQUEST", "对话消息数量无效")
    var characters = 0
    for (index in 0 until messages.length()) {
      val message = messages.optJSONObject(index) ?: return failure("INVALID_REQUEST", "对话消息无效")
      if (message.optString("role") !in listOf("system", "user", "assistant") || message.opt("content") !is String) return failure("INVALID_REQUEST", "对话消息无效")
      characters += message.getString("content").length
      if (characters > 128000) return failure("INVALID_REQUEST", "对话内容过长，请精简后重试")
    }
    val temperature = input.optDouble("temperature", 1.0)
    val maxTokens = input.optDouble("maxTokens", 2048.0)
    if (!temperature.isFinite() || temperature !in 0.0..2.0 || !maxTokens.isFinite() || maxTokens !in 1.0..8192.0 || maxTokens % 1.0 != 0.0) return failure("INVALID_REQUEST", "AI 生成参数无效")
    val endpoint = endpoint(chosen.getString("baseUrl"))
    val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
    if (input.has("temperature")) body.put("temperature", temperature)
    if (input.has("maxTokens")) body.put("max_tokens", maxTokens.toInt())
    var connection: HttpURLConnection? = null
    return try {
      val request = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      instanceFollowRedirects = false
      connectTimeout = 20000
      readTimeout = 120000
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Authorization", "Bearer ${chosen.optString("apiKey")}")
      }
      connection = request
      request.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
      val code = request.responseCode
      if (code !in 200..299) {
        // Provider error bodies may echo credentials or private prompts; never return them.
        when (code) {
          401, 403 -> failure("AUTH_FAILED", "AI 引擎认证失败，请检查 API Key 或模型权限")
          429 -> failure("RATE_LIMITED", "AI 引擎额度不足或请求过于频繁，请稍后重试")
          408, 504 -> failure("TIMEOUT", "AI 响应超时，请稍后重试")
          else -> failure("PROVIDER_ERROR", "AI 引擎请求失败（HTTP $code），请检查地址、模型和生成参数")
        }
      } else {
        val text = request.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
          val result = StringBuilder(); val buffer = CharArray(8192)
          while (true) { val size = reader.read(buffer); if (size < 0) break; result.append(buffer, 0, size); if (result.length > 2 * 1024 * 1024) return failure("INVALID_RESPONSE", "模型响应过大，请减少输出长度") }
          result.toString()
        }
        val message = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val content = message?.opt("content") as? String
        if (content.isNullOrBlank()) failure("INVALID_RESPONSE", "模型未返回可用文本，请重试")
        else JSONObject().put("ok", true).put("text", content).put("providerId", chosen.getString("id")).put("model", model)
      }
    } catch (_: SocketTimeoutException) {
      failure("TIMEOUT", "AI 响应超时，请稍后重试")
    } catch (_: IOException) {
      failure("NETWORK_ERROR", "无法连接 AI 引擎，请检查网络和模型地址")
    } catch (error: Exception) {
      failure("INVALID_RESPONSE", "AI 引擎返回的数据格式不兼容")
    } finally {
      connection?.disconnect()
    }
  }

  private fun failure(code: String, message: String) = JSONObject().put("ok", false).put("code", code).put("error", message)

  private fun secureEndpoint(baseUrl: String): Boolean = try {
    val url = URL(baseUrl)
    url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null
  } catch (_: Exception) { false }

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
