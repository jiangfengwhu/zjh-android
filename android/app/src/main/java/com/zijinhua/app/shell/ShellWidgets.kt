package com.zijinhua.app.shell

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.zijinhua.app.MainActivity
import com.zijinhua.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistent RemoteViews. Launcher updates never create a ReactHost. */
object ShellWidgets {
  val methods = setOf("widgetStatus", "widgetSet", "widgetPin", "widgetList", "widgetRemove", "removeApplicationWidgets")
  private const val STORE = "application-widgets"
  const val PIN = "com.zijinhua.app.WIDGET_PINNED"
  private fun prefs(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
  private fun root(context: Context) = JSONObject(prefs(context).getString("data", "{}") ?: "{}")
  private fun save(context: Context, value: JSONObject) { check(prefs(context).edit().putString("data", value.toString()).commit()) }
  private fun component(context: Context) = ComponentName(context, ApplicationWidgetProvider::class.java)
  private fun result(error: String? = null) = JSONObject().put("ok", error == null).apply { if (error != null) put("error", error) }
  @Synchronized fun call(context: Context, method: String, input: JSONObject): JSONObject {
    val appId = input.getString("appId").also { require(it.matches(Regex("[a-z][a-z0-9-]{0,63}"))) }
    val manager = AppWidgetManager.getInstance(context)
    if (method == "widgetStatus") return result().put("supported", manager.isRequestPinAppWidgetSupported)
    val data = root(context)
    val configs = data.optJSONObject("configs") ?: JSONObject().also { data.put("configs", it) }
    val bindings = data.optJSONObject("bindings") ?: JSONObject().also { data.put("bindings", it) }
    if (method == "widgetList") {
      val items = JSONArray()
      val installed = manager.getAppWidgetIds(component(context)).toSet()
      configs.keys().asSequence().filter { it.startsWith("$appId:") }.forEach { key ->
        items.put(JSONObject().put("id", key.substringAfter(':')).put("instances", JSONArray(bindings.keys().asSequence().filter { bindings.optString(it) == key && it.toIntOrNull() in installed }.map { it.toInt() }.toList())))
      }
      return result().put("items", items)
    }
    if (method == "removeApplicationWidgets") {
      configs.keys().asSequence().filter { it.startsWith("$appId:") }.toList().forEach { configs.remove(it); image(context, it).delete() }
      save(context, data); manager.getAppWidgetIds(component(context)).forEach { render(context, it) }; return result()
    }
    val id = input.getString("id").also { require(it.matches(Regex("[A-Za-z0-9._-]{1,60}"))) }
    val key = "$appId:$id"
    if (method == "widgetRemove") {
      configs.remove(key); image(context, key).delete(); save(context, data)
      bindings.keys().asSequence().filter { bindings.optString(it) == key }.forEach { render(context, it.toInt()) }
      return result()
    }
    if (method == "widgetSet") {
      require(configs.has(key) || configs.length() < 64 && configs.keys().asSequence().count { it.startsWith("$appId:") } < 8)
      val title = input.getString("title").also { require(it.isNotBlank() && it.length <= 60) }
      val body = input.optString("body").also { require(it.length <= 500) }
      val params = input.optJSONObject("params") ?: JSONObject()
      require(params.toString().toByteArray().size <= 4096)
      val config = JSONObject().put("appId", appId).put("title", title).put("body", body).put("params", params)
      for (color in listOf("backgroundColor", "textColor")) if (input.has(color)) {
        val value = input.getString(color).also { require(it.matches(Regex("#[A-Fa-f0-9]{6}"))) }; config.put(color, value)
      }
      if (input.has("progress")) config.put("progress", input.getDouble("progress").also { require(it.isFinite() && it in 0.0..1.0) })
      val oldImage = image(context, key)
      if (input.has("path")) {
        val source = File(input.getString("path")).canonicalFile
        val appRoot = File(context.filesDir, "applications/$appId").canonicalFile
        require(source.path.startsWith(appRoot.path + File.separator) && source.isFile && source.length() <= 20 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 320) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        try { oldImage.parentFile!!.mkdirs(); val tmp = File(oldImage.path + ".tmp"); tmp.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }; java.nio.file.Files.move(tmp.toPath(), oldImage.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        finally { bitmap.recycle() }
        config.put("image", true)
      } else oldImage.delete()
      configs.put(key, config); save(context, data)
      bindings.keys().asSequence().filter { bindings.optString(it) == key }.forEach { render(context, it.toInt()) }
      return result()
    }
    if (!configs.has(key)) return result("unavailable")
    if (!manager.isRequestPinAppWidgetSupported) return result("unsupported")
    val callback = Intent(context, ApplicationWidgetProvider::class.java).setAction(PIN).setData(Uri.parse("zijinhua-widget://pin/${Uri.encode(key)}")).putExtra("key", key)
    val pending = PendingIntent.getBroadcast(context, 0, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    return result().put("requested", manager.requestPinAppWidget(component(context), null, pending))
  }
  private fun image(context: Context, key: String) = File(context.filesDir, "widget-images/${Uri.encode(key)}.png")
  @Synchronized fun bind(context: Context, id: Int, key: String) {
    if (AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider != component(context)) return
    val data = root(context)
    if (data.optJSONObject("configs")?.has(key) != true) return
    val bindings = data.optJSONObject("bindings") ?: JSONObject().also { data.put("bindings", it) }
    bindings.put(id.toString(), key); save(context, data); render(context, id)
  }
  @Synchronized fun deleted(context: Context, ids: IntArray) {
    val data = root(context); val bindings = data.optJSONObject("bindings") ?: return
    ids.forEach { bindings.remove(it.toString()) }; save(context, data)
  }
  @Synchronized fun restored(context: Context, old: IntArray, new: IntArray) {
    val data = root(context); val bindings = data.optJSONObject("bindings") ?: return
    old.indices.forEach { index -> bindings.optString(old[index].toString()).takeIf { it.isNotEmpty() }?.let { bindings.put(new[index].toString(), it) }; bindings.remove(old[index].toString()) }
    save(context, data); new.forEach { render(context, it) }
  }
  fun refreshAll(context: Context) {
    AppWidgetManager.getInstance(context).getAppWidgetIds(component(context)).forEach { render(context, it) }
  }
  @Synchronized fun render(context: Context, id: Int) {
    val data = root(context); val key = data.optJSONObject("bindings")?.optString(id.toString()) ?: ""
    val config = data.optJSONObject("configs")?.optJSONObject(key)
    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
    val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
    val compact = height < 140
    val views = RemoteViews(context.packageName, if (compact) R.layout.application_widget_compact else R.layout.application_widget)
    fun color(viewId: Int, method: String, custom: String?, light: Int, dark: Int) {
      if (!custom.isNullOrEmpty()) views.setInt(viewId, method, Color.parseColor(custom))
      else if (android.os.Build.VERSION.SDK_INT >= 31) views.setColorInt(viewId, method, light, dark)
      // Earlier versions use day/night resources when the launcher inflates the view.
    }
    color(R.id.widget_root, "setBackgroundColor", config?.optString("backgroundColor"), Color.WHITE, Color.rgb(23, 24, 28))
    color(R.id.widget_title, "setTextColor", config?.optString("textColor"), Color.rgb(32, 33, 40), Color.rgb(248, 248, 250))
    color(R.id.widget_body, "setTextColor", config?.optString("textColor"), Color.rgb(32, 33, 40), Color.rgb(248, 248, 250))
    views.setTextViewText(R.id.widget_title, config?.optString("title") ?: "生花")
    views.setTextViewText(R.id.widget_body, config?.optString("body") ?: "请在应用内添加桌面小组件")
    views.setViewVisibility(R.id.widget_progress, if (config?.has("progress") == true) View.VISIBLE else View.GONE)
    views.setProgressBar(R.id.widget_progress, 100, ((config?.optDouble("progress", 0.0) ?: 0.0) * 100).toInt(), false)
    val bitmap = if (config?.optBoolean("image") == true) BitmapFactory.decodeFile(image(context, key).path) else null
    views.setViewVisibility(R.id.widget_image, if (bitmap != null && !compact) View.VISIBLE else View.GONE)
    if (bitmap != null) views.setImageViewBitmap(R.id.widget_image, bitmap)
    val url = Uri.Builder().scheme("zijinhua").authority("applications").appendPath(config?.optString("appId") ?: "")
      .apply { config?.optJSONObject("params")?.let { appendQueryParameter("p", it.toString()) } }.build()
    val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    views.setOnClickPendingIntent(R.id.widget_root, if (config != null) PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else null)
    AppWidgetManager.getInstance(context).updateAppWidget(id, views)
  }
}

class ApplicationWidgetProvider : AppWidgetProvider() {
  // One PendingResult per broadcast (restore may otherwise invoke both restored and update).
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action
    if (action !in setOf(ShellWidgets.PIN, AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_DELETED,
        AppWidgetManager.ACTION_APPWIDGET_RESTORED, AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
      super.onReceive(context, intent); return
    }
    val pending = goAsync()
    executor.execute {
      try {
        runCatching {
          when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> ShellWidgets.refreshAll(context)
            ShellWidgets.PIN -> ShellWidgets.bind(context, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1), intent.getStringExtra("key") ?: "")
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> (intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: intArrayOf()).forEach { ShellWidgets.render(context, it) }
            AppWidgetManager.ACTION_APPWIDGET_DELETED -> ShellWidgets.deleted(context, intArrayOf(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)))
            AppWidgetManager.ACTION_APPWIDGET_RESTORED -> {
              val old = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS) ?: intArrayOf()
              val new = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: intArrayOf()
              if (old.size == new.size) ShellWidgets.restored(context, old, new)
            }
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> ShellWidgets.render(context, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
          }
        }
      } finally { pending.finish() }
    }
  }
  companion object { private val executor = java.util.concurrent.Executors.newSingleThreadExecutor() }
}
