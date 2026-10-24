package com.futurequestr.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

object Net {
    private val base: String get() = BuildConfig.WORKER_URL.trimEnd('/')

    fun http(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null, timeoutMs: Int = 15000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    fun api(method: String, path: String, body: String? = null, timeoutMs: Int = 15000): Pair<Int, String> {
        if (base.isEmpty()) throw IllegalStateException("This build has no server address yet. Run 5_deploy_server.bat, then rebuild.")
        val h = mutableMapOf("Authorization" to "Bearer ${Store.token}")
        if (body != null) h["Content-Type"] = "application/json"
        return http(method, base + path, h, body, timeoutMs)
    }

    fun syncClock() {
        try {
            val (c, b) = http("GET", base + "/time", timeoutMs = 5000)
            if (c == 200) {
                Store.clockOffset = JSONObject(b).getLong("now") - System.currentTimeMillis()
                Store.clockKnown = true
            }
        } catch (e: Exception) { }
    }

    // ---------- GitHub device-flow sign-in ----------
    data class DeviceCode(val deviceCode: String, val userCode: String, val uri: String, val interval: Int, val expiresIn: Int)
    data class TokenResult(val status: String, val token: String? = null, val error: String = "")

    private val formHeaders = mapOf("Accept" to "application/json", "Content-Type" to "application/x-www-form-urlencoded")

    suspend fun startDevice(): DeviceCode = withContext(Dispatchers.IO) {
        if (BuildConfig.CLIENT_ID.isEmpty()) throw IllegalStateException("This build is missing its GitHub Client ID. Add it to config.txt and rebuild.")
        val (_, t) = http("POST", "https://github.com/login/device/code", formHeaders, "client_id=" + URLEncoder.encode(BuildConfig.CLIENT_ID, "UTF-8") + "&scope=")
        val j = JSONObject(t)
        if (!j.has("device_code")) throw IllegalStateException(j.optString("error_description", "GitHub sign in could not start. Is Device Flow switched on for the GitHub App?"))
        DeviceCode(j.getString("device_code"), j.getString("user_code"), j.optString("verification_uri", "https://github.com/login/device"), j.optInt("interval", 5), j.optInt("expires_in", 900))
    }

    suspend fun pollToken(d: DeviceCode): TokenResult = withContext(Dispatchers.IO) {
        val body = "client_id=" + URLEncoder.encode(BuildConfig.CLIENT_ID, "UTF-8") +
            "&device_code=" + URLEncoder.encode(d.deviceCode, "UTF-8") +
            "&grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", "UTF-8")
        val (_, t) = http("POST", "https://github.com/login/oauth/access_token", formHeaders, body)
        val j = JSONObject(t)
        when {
            j.has("access_token") -> TokenResult("ok", j.getString("access_token"))
            j.optString("error") == "authorization_pending" -> TokenResult("pending")
            j.optString("error") == "slow_down" -> TokenResult("slow_down")
            else -> TokenResult("error", error = j.optString("error_description", j.optString("error", "Sign in failed")))
        }
    }

    suspend fun githubLogin(token: String): String = withContext(Dispatchers.IO) {
        val (c, t) = http("GET", "https://api.github.com/user", mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json", "User-Agent" to "FutureQuestr"))
        if (c != 200) throw IllegalStateException("GitHub did not accept this sign in (code $c).")
        JSONObject(t).optString("login", "player")
    }

    // ---------- AI ----------
    data class PlanResult(val quests: List<Quest>?, val provider: String = "", val error: String = "", val detail: String = "")

    fun plan(prompt: String): PlanResult {
        val today = LocalDate.parse(Store.todayStr())
        val body = JSONObject().put("prompt", prompt.take(800)).put("today", today.toString())
            .put("weekday", today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)).toString()
        val (c, t) = api("POST", "/ai/plan", body, 45000)
        if (c == 401) return PlanResult(null, error = "signin")
        if (c != 200) return PlanResult(null, error = "unavailable", detail = "the server answered with code $c")
        val j = JSONObject(t)
        if (!j.optBoolean("ok")) return PlanResult(null, error = j.optString("error", "unavailable"), detail = j.optString("detail", ""))
        val arr = j.getJSONArray("quests")
        val out = ArrayList<Quest>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val dur = o.optInt("duration", 30).coerceIn(10, 120)
            val eff = o.optDouble("effort", 1.0).coerceIn(0.7, 1.5)
            val parts = o.optString("start", "17:00").split(":")
            val sm = ((parts.getOrNull(0)?.toIntOrNull() ?: 17) * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)).coerceIn(0, 1439)
            val cat = o.optString("category", "study").let { if (it in Game.categories) it else "study" }
            val day = today.plusDays(o.optInt("dayOffset", 0).coerceIn(0, 6).toLong()).toString()
            out.add(Quest(UUID.randomUUID().toString(), o.optString("title", "Quest").replace(Regex("[\u2010-\u2015\u2212\\-]+"), " ").replace(Regex("\\s+"), " ").trim().take(60), day, sm, dur, cat,
                (Game.standardXp(dur) * eff).roundToInt(), eff, true, updatedAt = Store.now()))
        }
        return PlanResult(out, j.optString("provider"))
    }

    fun rate(title: String, duration: Int): Double? {
        return try {
            val body = JSONObject().put("title", title).put("duration", duration).toString()
            val (c, t) = api("POST", "/ai/rate", body, 20000)
            if (c != 200) return null
            val j = JSONObject(t)
            if (j.optBoolean("ok")) j.getDouble("effort") else null
        } catch (e: Exception) { null }
    }

    /** Built-in timetable used when every AI provider is unavailable. */
    fun templatePlan(): List<Quest> {
        val today = LocalDate.parse(Store.todayStr())
        val out = ArrayList<Quest>()
        fun q(off: Int, title: String, start: Int, dur: Int, cat: String) = out.add(
            Quest(UUID.randomUUID().toString(), title, today.plusDays(off.toLong()).toString(), start, dur, cat,
                Game.standardXp(dur), 1.0, false, updatedAt = Store.now())
        )
        for (d in 0..4) {
            q(d, "Study block", 17 * 60, 45, "study")
            q(d, "Move your body", 18 * 60 + 30, 20, "health")
            q(d, "Plan tomorrow", 21 * 60, 10, "study")
        }
        q(5, "Catch up and review", 11 * 60, 60, "study")
        q(5, "Message a friend", 16 * 60, 10, "communication")
        q(6, "Rest and recharge", 15 * 60, 60, "rest")
        q(6, "Plan the week", 19 * 60, 20, "study")
        return out
    }
}

object Sync {
    data class Conflict(val serverJson: String, val serverVersion: Int, val serverUpdated: Long,
                        val serverQuests: Int, val serverLevel: Int, val localQuests: Int, val localLevel: Int)

    var conflict by mutableStateOf<Conflict?>(null)
    var status by mutableStateOf("")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun schedule() {
        if (Store.token.isEmpty()) return
        job?.cancel()
        job = scope.launch { delay(3000); run() }
    }

    private fun summarize(json: String): Pair<Int, Int> = try {
        val o = JSONObject(json)
        var n = 0
        val a = o.optJSONArray("quests")
        if (a != null) for (i in 0 until a.length()) if (!a.getJSONObject(i).optBoolean("deleted")) n++
        n to (o.optJSONObject("gs")?.optInt("level", 1) ?: 1)
    } catch (e: Exception) { 0 to 1 }

    private fun makeConflict(version: Int, updated: Long, data: String) {
        val (n, l) = summarize(data)
        conflict = Conflict(data, version, updated, n, l, Store.visibleQuests().size, Store.gs.level)
    }

    private fun put(base: Int): String {
        val body = JSONObject().put("base", base).put("data", Store.exportAll()).toString()
        val (c, t) = Net.api("PUT", "/sync", body)
        if (c == 200) { Store.serverVersion = JSONObject(t).getInt("version"); Store.dirty = false; return "pushed" }
        if (c == 409) {
            val j = JSONObject(t)
            if (j.has("data")) makeConflict(j.getInt("version"), j.optLong("updatedAt"), j.getString("data"))
            return "conflict"
        }
        return "error $c"
    }

    fun run(): String {
        if (Store.token.isEmpty()) return "signed out"
        return try {
            val (code, body) = Net.api("GET", "/sync", timeoutMs = 8000)
            val r = when {
                code == 401 -> "auth"
                code == 404 -> put(0)
                code != 200 -> "error $code"
                else -> {
                    val j = JSONObject(body)
                    val sv = j.getInt("version")
                    if (sv == Store.serverVersion) {
                        if (Store.dirty) put(sv) else "ok"
                    } else if (!Store.dirty) {
                        Store.importAll(j.getString("data")); Store.serverVersion = sv; "pulled"
                    } else {
                        makeConflict(sv, j.optLong("updatedAt"), j.getString("data")); "conflict"
                    }
                }
            }
            status = r
            r
        } catch (e: Exception) { status = "offline"; "offline" }
    }

    fun keepServer() {
        val c = conflict ?: return
        Store.importAll(c.serverJson)
        Store.serverVersion = c.serverVersion
        Store.dirty = false
        conflict = null
    }

    fun keepLocal() {
        val c = conflict ?: return
        conflict = null
        scope.launch { try { put(c.serverVersion) } catch (e: Exception) { } }
    }
}

object Updater {
    data class Info(val version: String, val url: String, val note: String, val size: Long = 0L, val sha256: String = "")

    fun newer(a: String, b: String): Boolean {
        val x = a.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** Asks GitHub for the latest release. Returns an Info if it is newer than the installed version. */
    fun check(force: Boolean = false): Info? {
        val repo = BuildConfig.RELEASES_REPO
        if (repo.isEmpty()) return null
        val last = Store.prefs.getLong("lastUpdCheck", 0L)
        if (!force && System.currentTimeMillis() - last < 25 * 60 * 1000L) return pending()
        return try {
            val (c, t) = Net.http("GET", "https://api.github.com/repos/$repo/releases/latest",
                mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "FutureQuestr"), timeoutMs = 10000)
            Store.prefs.edit().putLong("lastUpdCheck", System.currentTimeMillis()).apply()
            if (c != 200) return pending()
            val j = JSONObject(t)
            val tag = j.getString("tag_name")
            val assets = j.getJSONArray("assets")
            var url = ""
            var size = 0L
            var sha = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    url = a.getString("browser_download_url")
                    size = a.optLong("size", 0L)
                    sha = a.optString("digest", "").removePrefix("sha256:")
                    if (sha.length != 64) sha = ""
                    break
                }
            }
            if (url.isNotEmpty() && newer(tag, BuildConfig.VERSION_NAME)) {
                Store.prefs.edit().putString("updVer", tag.removePrefix("v")).putString("updUrl", url)
                    .putString("updNote", j.optString("body", "")).putLong("updSize", size).putString("updSha", sha).apply()
            }
            pending()
        } catch (e: Exception) { pending() }
    }

    fun pending(): Info? {
        val v = Store.prefs.getString("updVer", "") ?: ""
        val u = Store.prefs.getString("updUrl", "") ?: ""
        if (v.isEmpty() || u.isEmpty() || !newer(v, BuildConfig.VERSION_NAME)) return null
        return Info(v, u, Store.prefs.getString("updNote", "") ?: "", Store.prefs.getLong("updSize", 0L), Store.prefs.getString("updSha", "") ?: "")
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
    }

    suspend fun downloadAndInstall(ctx: Context, url: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "update"); dir.mkdirs()
            val f = File(dir, "FutureQuestr.apk")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            c.disconnect()
            f
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
