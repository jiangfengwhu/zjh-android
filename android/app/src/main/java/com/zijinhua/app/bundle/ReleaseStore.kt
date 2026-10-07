package com.zijinhua.app.bundle

import android.content.Context
import android.util.Log
import com.zijinhua.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

/** 当前这一套里的一个 DEX 插件：类名和 DEX 文件。 */
data class PluginFile(val id: String, val className: String, val dex: File)

/**
 * 手机上的「整套」：一份 vendor（第三方库和 RN，冷启动的主包）、一份 app（我们自己的全部代码）、若干 DEX 插件。
 *
 * 磁盘布局都在 filesDir/release 下：
 * - blobs/<sha256>：包体，按内容命名，写入后不再改。
 * - docs/<名字>.json：一套的清单。current 指向现行的一套，previous 是上一套。
 * - incoming/<id>.json：已经下载齐全、等待换上的清单。
 * - live/vendor.bundle：现行 vendor 的副本。ReactHost 只在创建时读一次主包路径，重载时还读同一个路径，所以主包要放在固定位置。
 * 换新只是改 current 指针，包体不搬动；启动没走到 bundleReady 就回到 previous。
 */
class ReleaseStore(private val context: Context) {
  private val root = File(context.filesDir, "release")
  private val blobs = File(root, "blobs")
  private val docs = File(root, "docs")
  private val incoming = File(root, "incoming")
  private val live = File(root, "live")

  fun vendorPath(): String? {
    val file = File(live, "vendor.bundle")
    if (!file.isFile || file.length() == 0L) return null
    return file.absolutePath
  }

  fun appFile(): File {
    val active = readActive() ?: throw IllegalStateException("没有可用的一套")
    val app = active.optJSONObject("app") ?: throw IllegalStateException("这一套缺少 app 包，请重新安装或更新")
    val file = blob(app.getString("sha256"))
    if (!file.isFile || file.length() == 0L) throw IllegalStateException("app 包文件缺失")
    return file
  }

  fun plugins(): List<PluginFile> {
    val active = readActive() ?: return emptyList()
    val items = active.optJSONArray("plugins") ?: return emptyList()
    val found = mutableListOf<PluginFile>()
    for (index in 0 until items.length()) {
      val item = items.getJSONObject(index)
      val dex = blob(item.getString("sha256"))
      if (!dex.isFile) {
        Log.e(TAG, "plugin dex missing id=${item.optString("id")}")
        continue
      }
      found.add(PluginFile(item.getString("id"), item.getString("className"), dex))
    }
    return found
  }

  fun readActive(): JSONObject? {
    val name = readPointer("current") ?: return null
    return readDoc(name)
  }

  /** 冷启动最早调用：确认现行的一套可用，上次没启动成功就退回上一套，把下载好的新一套换上，并把主包放到固定位置。 */
  fun prepareBoot() {
    synchronized(STATE) {
      ensureReady()
      val pending = File(root, "boot.pending")
      if (pending.isFile) {
        Log.i(TAG, "previous boot did not become healthy, rolling back")
        // 旧版标记为 boot；新版标记绑定版本，切换中断时不误伤仍在运行的旧版本。
        val trial = pending.readText().trim()
        if (trial == "boot" || trial == readPointer("current")) rollbackAfterBadBoot()
        pending.delete()
      }
      try {
        promoteIfReady()
      } catch (error: Exception) {
        Log.e(TAG, "staged release dropped ${error.message}")
      }
      syncLive()
      root.mkdirs()
      writeAtomic(pending, (readPointer("current") ?: "boot").toByteArray(Charsets.UTF_8))
    }
  }

  /** JS 已经跑到 bundleReady：这一套启动成功，清掉启动标记并回收用不到的包体。 */
  fun markHealthy() {
    synchronized(STATE) {
      val pending = File(root, "boot.pending")
      if (pending.isFile) pending.delete()
      gc()
      Log.i(TAG, "release healthy id=${readActive()?.optString("id")}")
    }
  }

  fun pendingItem(): JSONObject? {
    val staged = readReadyStaged() ?: return null
    val active = readActive()
    if (active != null && sameContent(active, staged)) return null
    val have = if (active != null) blobShas(active) else emptySet()
    var bytes = 0L
    for (sha in blobShas(staged)) {
      if (sha !in have) bytes += blob(sha).length()
    }
    Log.i(TAG, "pending release id=${staged.optString("id")} bytes=$bytes")
    return JSONObject()
      .put("id", "release")
      .put("kind", "layer")
      .put("bytes", bytes)
      .put("sha256", staged.getString("id"))
  }

  /** 读线上清单，把这一套里本机还没有的包体下载齐，再记成「可以换上」。同一时刻只跑一个。 */
  fun stage() {
    STAGE.lock()
    try {
      stageLocked()
    } finally {
      STAGE.unlock()
    }
  }

  private fun stageLocked() {
    val text = httpGet("$EDGE/v1/release")
    Log.i(TAG, "release list from $EDGE blobs from $CDN")
    val release = JSONObject(text)
    validate(release)
    val id = release.getString("id")
    if (id in badIds()) {
      Log.i(TAG, "release skipped, it failed to boot before id=$id")
      clearIncoming(null)
      return
    }
    val active = readActive()
    if (active != null && sameContent(active, release)) {
      Log.i(TAG, "release already current id=$id")
      clearIncoming(null)
      return
    }
    val staged = readDoc(File(incoming, "$id.json"))
    if (staged != null && blobsPresent(staged)) {
      Log.i(TAG, "release already staged id=$id")
      return
    }
    val vendor = release.getJSONObject("vendor")
    ensureBlob(vendor.getString("sha256"), vendor.getLong("bytes"), "$CDN/v1/vendor/${vendor.getString("sha256")}", 120000)
    val app = release.getJSONObject("app")
    ensureBlob(app.getString("sha256"), app.getLong("bytes"), "$CDN/v1/app/${app.getString("sha256")}", 120000)
    val plugins = release.getJSONArray("plugins")
    for (index in 0 until plugins.length()) {
      val plugin = plugins.getJSONObject(index)
      val sha = plugin.getString("sha256")
      ensureBlob(sha, plugin.getLong("bytes"), "$CDN/v1/plugins/${plugin.getString("id")}/$sha", 60000)
    }
    incoming.mkdirs()
    writeAtomic(File(incoming, "$id.json"), text.toByteArray(Charsets.UTF_8))
    clearIncoming(id)
    Log.i(TAG, "release staged id=$id")
  }

  /** 把下载好的一套换成现行的：校验包体，写清单，改指针，把主包放到固定位置。 */
  fun promoteIfReady(): Boolean {
    synchronized(STATE) {
      val staged = readReadyStaged() ?: return false
      val id = staged.getString("id")
      val active = readActive()
      if (active != null && sameContent(active, staged)) {
        clearIncoming(null)
        Log.i(TAG, "release already active id=$id")
        return false
      }
      try {
        validate(staged)
        if (id in badIds()) throw IllegalArgumentException("这一套之前启动失败")
        verifyBlobs(staged)
      } catch (error: Exception) {
        File(incoming, "$id.json").delete()
        throw error
      }
      docs.mkdirs()
      writeAtomic(File(docs, "$id.json"), staged.toString().toByteArray(Charsets.UTF_8))
      val old = readPointer("current")
      if (old != null && old != id) writePointer("previous", old)
      // 冷启动和应用内重载都经过这里；在切换指针前持久化试运行标记。
      writeAtomic(File(root, "boot.pending"), id.toByteArray(Charsets.UTF_8))
      writePointer("current", id)
      File(incoming, "$id.json").delete()
      syncLive()
      gc()
      Log.i(TAG, "promoted release id=$id")
      return true
    }
  }

  private fun ensureReady() {
    val stamp = installedStamp()
    val recorded = File(root, "apk-stamp")
    val active = readActive()
    val usable = active != null && blobsPresent(active)
    if (recorded.isFile && recorded.readText().trim() == stamp && usable) return
    Log.i(TAG, "resetting to the release inside the apk stamp=$stamp usable=$usable")
    resetToSeed(stamp, keepBad = false)
  }

  /** 新装或升级了 APK，或者现行的一套坏了：清空，改用 APK 里自带的 vendor 和 app。 */
  private fun resetToSeed(stamp: String, keepBad: Boolean) {
    val bad = if (keepBad) badIds() else emptySet()
    root.deleteRecursively()
    legacyCleanup()
    blobs.mkdirs()
    docs.mkdirs()
    val vendor = copyAsset("index.android.bundle")
      ?: throw IllegalStateException("APK 里没有主包 index.android.bundle")
    val app = copyAsset("app.bundle")
    if (app == null) Log.e(TAG, "apk has no app.bundle, run npm run prepare:apk before building")
    val name = "seed-$stamp"
    val doc = JSONObject()
      .put("format", FORMAT)
      .put("runtimeVersion", BuildConfig.BUNDLE_RUNTIME_VERSION)
      .put("id", name)
      .put("vendor", JSONObject().put("sha256", vendor.first).put("bytes", vendor.second))
      .put("plugins", org.json.JSONArray())
    if (app != null) doc.put("app", JSONObject().put("sha256", app.first).put("bytes", app.second))
    writeAtomic(File(docs, "$name.json"), doc.toString().toByteArray(Charsets.UTF_8))
    writePointer("current", name)
    File(root, "apk-stamp").writeText(stamp)
    if (bad.isNotEmpty()) File(root, "bad.txt").writeText(bad.joinToString("\n"))
    Log.i(TAG, "seeded release $name vendor=${vendor.second} app=${app?.second ?: 0}")
  }

  /** 旧版本的目录（bundles、plugins）不再使用，装到新版本时一次性清掉。 */
  private fun legacyCleanup() {
    File(context.filesDir, "bundles").deleteRecursively()
    File(context.filesDir, "plugins").deleteRecursively()
    context.filesDir.listFiles()?.forEach { file ->
      if (file.name.startsWith("plugins-quarantine-")) file.deleteRecursively()
    }
  }

  private fun copyAsset(name: String): Pair<String, Long>? {
    return try {
      val tmp = File(blobs, "asset-$name.tmp")
      val digest = MessageDigest.getInstance("SHA-256")
      var total = 0L
      context.assets.open(name).use { input ->
        tmp.outputStream().use { output ->
          val buffer = ByteArray(65536)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            total += read
          }
        }
      }
      val sha = hex(digest.digest())
      val dest = blob(sha)
      if (dest.exists()) dest.delete()
      if (!tmp.renameTo(dest)) throw IllegalStateException("无法保存 $name")
      sha to total
    } catch (error: java.io.FileNotFoundException) {
      Log.i(TAG, "no asset $name")
      null
    }
  }

  private fun rollbackAfterBadBoot() {
    val name = readPointer("current")
    val active = readActive()
    val id = active?.optString("id").orEmpty()
    if (name != null && name.startsWith("seed-")) {
      Log.e(TAG, "the release inside the apk did not boot either, keeping it")
      return
    }
    if (id.isNotEmpty()) addBad(id)
    val previous = readPointer("previous")
    val previousDoc = if (previous != null) readDoc(previous) else null
    if (previous != null && previousDoc != null && blobsPresent(previousDoc)) {
      writePointer("current", previous)
      writePointer("previous", null)
      Log.i(TAG, "rolled back to $previous")
    } else {
      resetToSeed(installedStamp(), keepBad = true)
    }
    syncLive()
    gc()
  }

  /** 现行一套的 vendor 放到固定位置。启动时和每次换新后都会核对，中途断电也能自己补齐。 */
  private fun syncLive() {
    val active = readActive() ?: return
    val sha = active.getJSONObject("vendor").getString("sha256")
    val target = File(live, "vendor.bundle")
    val marker = File(live, "vendor.sha256")
    if (target.isFile && marker.isFile && marker.readText().trim() == sha) return
    val source = blob(sha)
    if (!source.isFile) throw IllegalStateException("主包文件缺失")
    live.mkdirs()
    marker.delete()
    val tmp = File(live, "vendor.bundle.tmp")
    source.copyTo(tmp, overwrite = true)
    if (target.exists()) target.delete()
    if (!tmp.renameTo(target)) throw IllegalStateException("无法放置主包")
    marker.writeText(sha)
    Log.i(TAG, "live vendor sha256=$sha")
  }

  /** 删掉不再用的清单和包体：只留现行、上一套、以及已下载待换上的。下载进行中不回收。 */
  private fun gc() {
    if (!STAGE.tryLock()) {
      Log.i(TAG, "gc skipped while a download is running")
      return
    }
    try {
      val keepDocs = setOfNotNull(readPointer("current"), readPointer("previous"))
      docs.listFiles()?.forEach { file ->
        if (file.name.removeSuffix(".json") !in keepDocs) file.delete()
      }
      val keep = mutableSetOf<String>()
      for (name in keepDocs) {
        val doc = readDoc(name)
        if (doc != null) keep += blobShas(doc)
      }
      incoming.listFiles()?.forEach { file ->
        val doc = readDoc(file)
        if (doc != null) keep += blobShas(doc)
      }
      blobs.listFiles()?.forEach { file ->
        if (file.name !in keep && file.delete()) Log.i(TAG, "removed blob ${file.name.take(12)}")
      }
    } finally {
      STAGE.unlock()
    }
  }

  private fun clearIncoming(except: String?) {
    incoming.listFiles()?.forEach { file ->
      if (except == null || file.name != "$except.json") file.delete()
    }
  }

  private fun readReadyStaged(): JSONObject? {
    val files = incoming.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return null
    for (file in files.sortedByDescending { it.lastModified() }) {
      val doc = readDoc(file) ?: continue
      if (doc.optString("runtimeVersion") != BuildConfig.BUNDLE_RUNTIME_VERSION) continue
      if (blobsPresent(doc)) return doc
    }
    return null
  }

  private fun blobShas(doc: JSONObject): Set<String> {
    val shas = mutableSetOf<String>()
    doc.optJSONObject("vendor")?.optString("sha256")?.let { if (it.isNotEmpty()) shas += it }
    doc.optJSONObject("app")?.optString("sha256")?.let { if (it.isNotEmpty()) shas += it }
    val plugins = doc.optJSONArray("plugins")
    if (plugins != null) {
      for (index in 0 until plugins.length()) {
        val sha = plugins.getJSONObject(index).optString("sha256")
        if (sha.isNotEmpty()) shas += sha
      }
    }
    return shas
  }

  private fun blobsPresent(doc: JSONObject): Boolean {
    for (sha in blobShas(doc)) {
      val file = blob(sha)
      if (!file.isFile || file.length() == 0L) return false
    }
    return doc.optJSONObject("vendor") != null
  }

  private fun verifyBlobs(doc: JSONObject) {
    for (sha in blobShas(doc)) {
      val file = blob(sha)
      if (!file.isFile || file.length() == 0L) throw IllegalStateException("配套缺文件 ${sha.take(12)}")
      if (sha256(file) != sha) {
        file.delete()
        throw IllegalStateException("配套校验失败 ${sha.take(12)}")
      }
    }
  }

  private fun sameContent(left: JSONObject, right: JSONObject): Boolean {
    if (shaOf(left, "vendor") != shaOf(right, "vendor")) return false
    if (shaOf(left, "app") != shaOf(right, "app")) return false
    return pluginKeys(left) == pluginKeys(right)
  }

  private fun shaOf(doc: JSONObject, name: String): String = doc.optJSONObject(name)?.optString("sha256").orEmpty()

  private fun pluginKeys(doc: JSONObject): Set<String> {
    val keys = mutableSetOf<String>()
    val plugins = doc.optJSONArray("plugins") ?: return keys
    for (index in 0 until plugins.length()) {
      val plugin = plugins.getJSONObject(index)
      keys += "${plugin.optString("id")}:${plugin.optString("sha256")}"
    }
    return keys
  }

  private fun validate(release: JSONObject) {
    if (release.optInt("format") != FORMAT) throw IllegalArgumentException("配套格式不认识")
    if (release.optString("runtimeVersion") != BuildConfig.BUNDLE_RUNTIME_VERSION) {
      throw IllegalArgumentException("配套需要不同的原生运行时，请更新 APK")
    }
    val sdk = release.optJSONObject("applications")?.optString("sdk") ?: ""
    if (!sdk.matches(Regex("\\d+\\.\\d+\\.\\d+")) || sdk.substringBefore(".").toIntOrNull() != BuildConfig.APPLICATION_SDK_MAJOR) {
      throw IllegalArgumentException("宿主 SDK 大版本与 APK 不匹配，请更新紫金花")
    }
    if (!release.optString("id").matches(HEX)) throw IllegalArgumentException("配套编号不对")
    val vendor = release.optJSONObject("vendor") ?: throw IllegalArgumentException("配套缺少 vendor")
    requireBlob(vendor)
    if (!vendor.optString("map").matches(HEX)) throw IllegalArgumentException("配套缺少 vendor 编号")
    val app = release.optJSONObject("app") ?: throw IllegalArgumentException("配套缺少 app")
    requireBlob(app)
    if (app.optString("vendorMap") != vendor.optString("map")) throw IllegalArgumentException("app 和 vendor 编号对不上")
    val plugins = release.optJSONArray("plugins") ?: throw IllegalArgumentException("配套缺少插件清单")
    for (index in 0 until plugins.length()) {
      val plugin = plugins.optJSONObject(index) ?: throw IllegalArgumentException("配套插件不对")
      requireBlob(plugin)
      val pluginId = plugin.optString("id")
      if (!pluginId.matches(Regex("[a-z0-9-]+"))) throw IllegalArgumentException("配套插件编号不对")
      if (plugin.optString("dex") != "$pluginId.dex") throw IllegalArgumentException("配套插件文件不对")
      if (plugin.optString("className").isEmpty() || plugin.optString("className").length > 300) {
        throw IllegalArgumentException("配套插件缺少类名")
      }
    }
  }

  private fun requireBlob(item: JSONObject) {
    if (!item.optString("sha256").matches(HEX)) throw IllegalArgumentException("配套校验不对")
    if (item.optLong("bytes") <= 0L) throw IllegalArgumentException("配套大小不对")
  }

  private fun ensureBlob(sha: String, bytes: Long, url: String, readTimeoutMs: Int) {
    val file = blob(sha)
    if (file.isFile && file.length() == bytes) {
      Log.i(TAG, "blob already here ${sha.take(12)}")
      return
    }
    val data = httpGetBytes(url, readTimeoutMs)
    if (data.size.toLong() != bytes) throw IllegalStateException("配套大小不对 ${sha.take(12)}")
    if (sha256(data) != sha) throw IllegalStateException("配套校验失败 ${sha.take(12)}")
    blobs.mkdirs()
    writeAtomic(file, data)
    Log.i(TAG, "downloaded blob ${sha.take(12)} bytes=${data.size}")
  }

  private fun blob(sha: String): File = File(blobs, sha)

  private fun badIds(): Set<String> {
    val file = File(root, "bad.txt")
    if (!file.isFile) return emptySet()
    return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
  }

  private fun addBad(id: String) {
    val next = badIds() + id
    root.mkdirs()
    File(root, "bad.txt").writeText(next.joinToString("\n"))
    Log.i(TAG, "marked release as bad id=$id")
  }

  private fun readPointer(name: String): String? {
    val file = File(root, name)
    if (!file.isFile) return null
    val text = file.readText().trim()
    return if (text.isEmpty()) null else text
  }

  private fun writePointer(name: String, value: String?) {
    val file = File(root, name)
    if (value == null) {
      file.delete()
      return
    }
    writeAtomic(file, value.toByteArray(Charsets.UTF_8))
  }

  private fun readDoc(name: String): JSONObject? = readDoc(File(docs, "$name.json"))

  private fun readDoc(file: File): JSONObject? {
    if (!file.isFile || file.length() == 0L) return null
    return try {
      JSONObject(file.readText())
    } catch (error: Exception) {
      Log.i(TAG, "release document unreadable ${file.name} ${error.message}")
      null
    }
  }

  private fun writeAtomic(dest: File, bytes: ByteArray) {
    dest.parentFile?.mkdirs()
    val tmp = File(dest.parentFile, "${dest.name}.tmp")
    tmp.writeBytes(bytes)
    if (dest.exists() && !dest.delete()) Log.e(TAG, "could not replace ${dest.name}")
    if (!tmp.renameTo(dest)) {
      dest.writeBytes(bytes)
      tmp.delete()
    }
  }

  private fun installedStamp(): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return info.lastUpdateTime.toString()
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(65536)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return hex(digest.digest())
  }

  private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

  private fun httpGet(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10000
    connection.readTimeout = 20000
    connection.requestMethod = "GET"
    try {
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val body = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
      if (code == 404) throw IllegalStateException("没有配套清单")
      if (code !in 200..299) throw IllegalStateException("配套清单读取失败 $code ${body.take(180)}")
      return body
    } finally {
      connection.disconnect()
    }
  }

  private fun httpGetBytes(url: String, readTimeoutMs: Int): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10000
    connection.readTimeout = readTimeoutMs
    connection.requestMethod = "GET"
    try {
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val body = stream.use { it.readBytes() }
      if (code !in 200..299) throw IllegalStateException("配套下载失败 $code ${String(body, Charsets.UTF_8).take(180)}")
      return body
    } finally {
      connection.disconnect()
    }
  }

  private companion object {
    const val TAG = "ReleaseStore"
    const val FORMAT = 3
    const val EDGE = "https://zijinhua.297782.xyz"
    const val CDN = "https://zjh-cdn.297782.xyz"
    val HEX = Regex("[a-f0-9]{64}")
    val STATE = Any()
    val STAGE = ReentrantLock()
  }
}
