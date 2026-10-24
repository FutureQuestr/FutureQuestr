package com.futurequestr.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

data class Quest(
    val id: String, val title: String, val day: String, val startMin: Int, val durationMin: Int,
    val category: String, val xp: Int, val effort: Double = 1.0, val rated: Boolean = false,
    val done: Boolean = false, val awarded: Int = 0, val doneAt: Long = 0L,
    val deleted: Boolean = false, val updatedAt: Long = 0L
)

data class GameState(
    val level: Int = 1, val xpInLevel: Int = 0, val target: Int = 100, val totalXp: Int = 0,
    val streak: Int = 0, val bestStreak: Int = 0, val lastActiveDay: String = "",
    val badges: List<String> = emptyList(),
    val challengeDay: String = "", val challengesDone: List<String> = emptyList(), val challengeTotal: Int = 0,
    val dailyXpDay: String = "", val dailyXp: Int = 0, val luckyDay: String = "", val luckyCount: Int = 0,
    val totalDone: Int = 0, val totalMinutes: Int = 0, val focusSessions: Int = 0,
    val recentXp: List<String> = emptyList()
)

sealed class Reward {
    data class Level(val level: Int) : Reward()
    data class BadgeEarned(val badge: Game.Badge) : Reward()
}

data class Outcome(val ok: Boolean, val message: String = "", val xp: Int = 0, val lucky: Boolean = false)

fun fmtTime24(min: Int) = "%02d:%02d".format((min / 60) % 24, min % 60)
fun fmtTime12(min: Int): String {
    val h = (min / 60) % 24
    val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
    return "%d:%02d %s".format(h12, min % 60, if (h < 12) "AM" else "PM")
}
/** Shows a time of day in whichever format the person chose (12 or 24 hour). */
fun fmtTime(min: Int) = if (Store.use24h) fmtTime24(min) else fmtTime12(min)
fun dayStr(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
fun questStartMs(q: Quest): Long =
    LocalDate.parse(q.day).atStartOfDay(ZoneId.systemDefault()).plusMinutes(q.startMin.toLong()).toInstant().toEpochMilli()

object Store {
    private var inited = false
    private lateinit var ctx: Context
    lateinit var prefs: SharedPreferences

    var quests by mutableStateOf(listOf<Quest>())
    var gs by mutableStateOf(GameState())
    var rewards by mutableStateOf(listOf<Reward>())
    var muted by mutableStateOf(false)
    var use24h by mutableStateOf(false)
    var themeMode by mutableStateOf(0)     // 0 follow the phone, 1 light, 2 dark
    var updateMode by mutableStateOf(1)    // 0 ask first, 1 download in background, 2 install automatically
    @Volatile var foreground = false

    // ---------- init / persistence ----------
    fun init(c: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            ctx = c.applicationContext
            prefs = ctx.getSharedPreferences("fq", 0)
            muted = prefs.getBoolean("muted", false)
            themeMode = prefs.getInt("themeMode", 0)
            updateMode = prefs.getInt("updateMode", 1)
            use24h = if (prefs.contains("use24h")) prefs.getBoolean("use24h", false) else android.text.format.DateFormat.is24HourFormat(ctx)
            loadData()
            inited = true
        }
    }

    private fun dataFile() = File(ctx.filesDir, "data.json")

    private fun questJson(q: Quest) = JSONObject().put("id", q.id).put("title", q.title).put("day", q.day)
        .put("startMin", q.startMin).put("durationMin", q.durationMin).put("category", q.category)
        .put("xp", q.xp).put("effort", q.effort).put("rated", q.rated).put("done", q.done)
        .put("awarded", q.awarded).put("doneAt", q.doneAt).put("deleted", q.deleted).put("updatedAt", q.updatedAt)

    private fun questFrom(o: JSONObject) = Quest(
        o.getString("id"), o.getString("title"), o.getString("day"), o.getInt("startMin"), o.getInt("durationMin"),
        o.optString("category", "study"), o.optInt("xp", 30), o.optDouble("effort", 1.0), o.optBoolean("rated"),
        o.optBoolean("done"), o.optInt("awarded"), o.optLong("doneAt"), o.optBoolean("deleted"), o.optLong("updatedAt")
    )

    private fun strList(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    private fun gsJson(g: GameState) = JSONObject().put("level", g.level).put("xpInLevel", g.xpInLevel).put("target", g.target)
        .put("totalXp", g.totalXp).put("streak", g.streak).put("bestStreak", g.bestStreak).put("lastActiveDay", g.lastActiveDay)
        .put("badges", JSONArray(g.badges)).put("challengeDay", g.challengeDay).put("challengesDone", JSONArray(g.challengesDone))
        .put("challengeTotal", g.challengeTotal).put("dailyXpDay", g.dailyXpDay).put("dailyXp", g.dailyXp)
        .put("luckyDay", g.luckyDay).put("luckyCount", g.luckyCount).put("totalDone", g.totalDone)
        .put("totalMinutes", g.totalMinutes).put("focusSessions", g.focusSessions).put("recentXp", JSONArray(g.recentXp))

    private fun gsFrom(o: JSONObject) = GameState(
        o.optInt("level", 1), o.optInt("xpInLevel"), o.optInt("target", 100), o.optInt("totalXp"),
        o.optInt("streak"), o.optInt("bestStreak"), o.optString("lastActiveDay"), strList(o.optJSONArray("badges")),
        o.optString("challengeDay"), strList(o.optJSONArray("challengesDone")), o.optInt("challengeTotal"),
        o.optString("dailyXpDay"), o.optInt("dailyXp"), o.optString("luckyDay"), o.optInt("luckyCount"),
        o.optInt("totalDone"), o.optInt("totalMinutes"), o.optInt("focusSessions"), strList(o.optJSONArray("recentXp"))
    )

    fun exportAll(): String {
        val arr = JSONArray()
        quests.forEach { arr.put(questJson(it)) }
        return JSONObject().put("v", 1).put("quests", arr).put("gs", gsJson(gs)).toString()
    }

    fun importAll(json: String) {
        val o = JSONObject(json)
        val arr = o.optJSONArray("quests") ?: JSONArray()
        quests = List(arr.length()) { questFrom(arr.getJSONObject(it)) }
        gs = o.optJSONObject("gs")?.let { gsFrom(it) } ?: GameState()
        save()
    }

    private fun loadData() {
        try {
            val f = dataFile()
            if (f.exists()) importAll(f.readText())
        } catch (e: Exception) {
            quests = emptyList(); gs = GameState()
        }
    }

    fun save() {
        try { dataFile().writeText(exportAll()) } catch (e: Exception) { }
    }

    private fun touch() {
        save(); dirty = true; Sync.schedule()
    }

    // ---------- backup (export / import) ----------
    data class BackupInfo(val quests: Int, val level: Int, val createdAt: Long, val json: JSONObject)

    /** Everything stored on this device that matters: Quests, XP, level, streak, badges and settings. It never includes the sign in. */
    fun exportBackup(appVersion: String): String {
        val settings = JSONObject().put("use24h", use24h).put("muted", muted).put("brand", brand).put("themeMode", themeMode).put("updateMode", updateMode)
        return JSONObject().put("app", "FutureQuestr").put("backupVersion", 1).put("appVersion", appVersion)
            .put("createdAt", now()).put("data", JSONObject(exportAll())).put("settings", settings).toString(2)
    }

    fun readBackup(text: String): BackupInfo {
        val bad = IllegalArgumentException("That file is not a FutureQuestr backup.")
        if (text.isBlank() || text.length > 5_000_000) throw bad
        try {
            val o = JSONObject(text)
            if (o.optString("app") != "FutureQuestr" || !o.has("data")) throw bad
            if (o.optInt("backupVersion", 1) > 1) throw IllegalArgumentException("This backup was made by a newer version of FutureQuestr. Update the app first.")
            val data = o.getJSONObject("data")
            val arr = data.optJSONArray("quests") ?: JSONArray()
            var live = 0
            for (i in 0 until arr.length()) {
                val q = questFrom(arr.getJSONObject(i))   // makes sure every Quest can be read
                if (!q.deleted) live++
            }
            val g = data.optJSONObject("gs")?.let { gsFrom(it) } ?: GameState()
            return BackupInfo(live, g.level, o.optLong("createdAt"), o)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw bad
        }
    }

    /** merge = false replaces everything with the backup. merge = true keeps what is here and adds what is missing. */
    fun applyBackup(info: BackupInfo, merge: Boolean) {
        val data = info.json.getJSONObject("data")
        val arr = data.optJSONArray("quests") ?: JSONArray()
        val theirs = List(arr.length()) { questFrom(arr.getJSONObject(it)) }
        val theirGs = data.optJSONObject("gs")?.let { gsFrom(it) } ?: GameState()
        if (merge) {
            val map = LinkedHashMap<String, Quest>()
            quests.forEach { map[it.id] = it }
            theirs.forEach { t -> val mine = map[t.id]; if (mine == null || t.updatedAt > mine.updatedAt) map[t.id] = t }
            quests = map.values.toList()
            if (theirGs.totalXp > gs.totalXp) gs = theirGs
        } else {
            quests = theirs
            gs = theirGs
        }
        info.json.optJSONObject("settings")?.let { st ->
            if (st.has("use24h")) { use24h = st.getBoolean("use24h"); pb("use24h", use24h) }
            if (st.has("muted")) applyMuted(st.getBoolean("muted"))
            if (st.has("themeMode")) applyThemeMode(st.getInt("themeMode").coerceIn(0, 2))
            if (st.has("updateMode")) applyUpdateMode(st.getInt("updateMode").coerceIn(0, 2))
            val b = st.optString("brand", "")
            if (b.isNotEmpty()) brand = b
        }
        touch()
    }

    fun resetData() {
        quests = emptyList(); gs = GameState(); rewards = emptyList()
        save(); dirty = false; serverVersion = 0
    }

    // ---------- simple prefs ----------
    private fun s(k: String) = prefs.getString(k, "") ?: ""
    private fun ps(k: String, v: String) = prefs.edit().putString(k, v).apply()
    private fun b(k: String) = prefs.getBoolean(k, false)
    private fun pb(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()

    var token: String get() = s("token"); set(v) = ps("token", v)
    var login: String get() = s("login"); set(v) = ps("login", v)
    var brand: String get() = s("brand").ifEmpty { Perms.detectBrand() }; set(v) = ps("brand", v)
    var ageOk: Boolean get() = b("ageOk"); set(v) = pb("ageOk", v)
    var underage: Boolean get() = b("underage"); set(v) = pb("underage", v)
    var permsDone: Boolean get() = b("permsDone"); set(v) = pb("permsDone", v)
    var restrictedAck: Boolean get() = b("restrictedAck"); set(v) = pb("restrictedAck", v)
    var dirty: Boolean get() = b("dirty"); set(v) = pb("dirty", v)
    var serverVersion: Int get() = prefs.getInt("serverVersion", 0); set(v) = prefs.edit().putInt("serverVersion", v).apply()
    var clockOffset: Long get() = prefs.getLong("clockOffset", 0L); set(v) = prefs.edit().putLong("clockOffset", v).apply()
    var clockKnown: Boolean get() = b("clockKnown"); set(v) = pb("clockKnown", v)

    fun applyMuted(v: Boolean) { muted = v; pb("muted", v) }
    fun applyUse24h(v: Boolean) { use24h = v; pb("use24h", v) }
    fun applyThemeMode(v: Int) { themeMode = v; prefs.edit().putInt("themeMode", v).apply() }
    fun applyUpdateMode(v: Int) { updateMode = v; prefs.edit().putInt("updateMode", v).apply() }
    fun signOut() { token = ""; login = "" }

    // ---------- time ----------
    fun now(): Long = System.currentTimeMillis() + (if (clockKnown) clockOffset else 0L)
    fun todayStr(): String = dayStr(now())
    val clockSuspicious: Boolean get() = clockKnown && abs(clockOffset) > 300_000L

    // ---------- quests ----------
    fun visibleQuests() = quests.filter { !it.deleted }

    fun minutesOn(day: String, excludeId: String?): Int =
        visibleQuests().filter { it.day == day && it.id != excludeId }.sumOf { it.durationMin }

    fun saveQuest(q: Quest, isNew: Boolean): Quest {
        var nq = q.copy(updatedAt = now())
        if (isNew) {
            quests = quests + nq
        } else {
            val old = quests.firstOrNull { it.id == q.id }
            if (old != null && (old.title != q.title || old.durationMin != q.durationMin)) {
                nq = nq.copy(xp = Game.standardXp(q.durationMin), effort = 1.0, rated = false)
            }
            quests = quests.map { if (it.id == q.id) nq else it }
        }
        touch()
        return nq
    }

    fun addMany(list: List<Quest>) {
        quests = quests + list
        touch()
    }

    fun deleteQuest(id: String) {
        quests = quests.map { if (it.id == id) it.copy(deleted = true, updatedAt = now()) else it }
        touch()
    }

    fun undoDelete(id: String) {
        quests = quests.map { if (it.id == id) it.copy(deleted = false, updatedAt = now()) else it }
        touch()
    }

    fun applyRating(id: String, effort: Double) {
        val q = quests.firstOrNull { it.id == id } ?: return
        if (q.done || q.rated) return
        val e = effort.coerceIn(0.7, 1.5)
        quests = quests.map { if (it.id == id) it.copy(effort = e, xp = (Game.standardXp(it.durationMin) * e).roundToInt(), rated = true) else it }
        touch()
    }

    // ---------- XP engine ----------
    fun effectiveStreak(): Int {
        val t = todayStr()
        val y = LocalDate.parse(t).minusDays(1).toString()
        return if (gs.lastActiveDay == t || gs.lastActiveDay == y) gs.streak else 0
    }

    private fun nextStreak(g: GameState, today: String): Int {
        if (g.lastActiveDay == today) return maxOf(g.streak, 1)
        val y = LocalDate.parse(today).minusDays(1).toString()
        return if (g.lastActiveDay == y) g.streak + 1 else 1
    }

    private fun paceFactor(g: GameState): Double {
        val r = g.recentXp.mapNotNull { it.substringAfter(":", "").toIntOrNull() }.takeLast(7)
        if (r.size < 3) return 1.0
        return (r.average() / 200.0).coerceIn(0.85, 1.15)
    }

    private fun addXp(g0: GameState, xp: Int): GameState {
        var g = g0
        var lvl = g.level
        var inLvl = g.xpInLevel + xp
        var target = g.target
        var ups = 0
        while (inLvl >= target) {
            inLvl -= target; lvl++; ups++
            target = Game.target(lvl, paceFactor(g))
        }
        if (ups > 0) rewards = rewards + Reward.Level(lvl)
        val t = todayStr()
        val rec = g.recentXp.toMutableList()
        val idx = rec.indexOfFirst { it.startsWith("$t:") }
        if (idx >= 0) rec[idx] = "$t:" + ((rec[idx].substringAfter(":").toIntOrNull() ?: 0) + xp) else rec.add("$t:$xp")
        g = g.copy(level = lvl, xpInLevel = inLvl, target = target, totalXp = g.totalXp + xp, recentXp = rec.takeLast(14))
        return g
    }

    private fun checkBadges() {
        val earned = gs.badges.toMutableList()
        for (bd in Game.badges) {
            if (bd.id !in earned && bd.test(gs)) {
                earned.add(bd.id)
                rewards = rewards + Reward.BadgeEarned(bd)
            }
        }
        if (earned.size != gs.badges.size) gs = gs.copy(badges = earned)
    }

    fun complete(id: String): Outcome {
        val q = quests.firstOrNull { it.id == id } ?: return Outcome(false)
        if (q.done || q.deleted) return Outcome(false)
        val now = now()
        if (now < questStartMs(q)) return Outcome(false, "This Quest starts at ${fmtTime(q.startMin)}. Come back then.")
        val today = dayStr(now)
        var g = gs
        var xp = 0
        var lucky = false
        var msg = ""
        if (q.day == today) {
            val streakAfter = nextStreak(g, today)
            var mult = 1.0
            if (!clockSuspicious) mult += 0.10
            mult += minOf(streakAfter, 10) * 0.05
            xp = (q.xp * mult).roundToInt()
            val lc = if (g.luckyDay == today) g.luckyCount else 0
            if (!clockSuspicious && lc < 3 && Random.nextInt(8) == 0) {
                xp += (q.xp * 0.25).roundToInt(); lucky = true
                g = g.copy(luckyDay = today, luckyCount = lc + 1)
            }
            val used = if (g.dailyXpDay == today) g.dailyXp else 0
            xp = xp.coerceAtMost(maxOf(Game.DAILY_CAP - used, 0))
            g = g.copy(streak = streakAfter, bestStreak = maxOf(g.bestStreak, streakAfter), lastActiveDay = today,
                dailyXpDay = today, dailyXp = used + xp)
            if (xp == 0) msg = "Daily XP cap reached. Great work though."
        } else {
            msg = "Marked done. XP only counts on a Quest's own day."
        }
        g = g.copy(totalDone = g.totalDone + 1, totalMinutes = g.totalMinutes + q.durationMin)
        gs = addXp(g, xp)
        quests = quests.map { if (it.id == id) it.copy(done = true, awarded = xp, doneAt = now, updatedAt = now) else it }
        checkBadges()
        touch()
        return Outcome(true, msg, xp, lucky)
    }

    fun uncomplete(id: String) {
        val q = quests.firstOrNull { it.id == id } ?: return
        if (!q.done) return
        var g = gs
        var lvl = g.level
        var inLvl = g.xpInLevel - q.awarded
        var target = g.target
        while (inLvl < 0 && lvl > 1) {
            lvl--; target = Game.target(lvl, 1.0); inLvl += target
        }
        if (inLvl < 0) inLvl = 0
        val today = todayStr()
        g = g.copy(level = lvl, xpInLevel = inLvl, target = target, totalXp = maxOf(0, g.totalXp - q.awarded),
            totalDone = maxOf(0, g.totalDone - 1), totalMinutes = maxOf(0, g.totalMinutes - q.durationMin),
            dailyXp = if (g.dailyXpDay == today) maxOf(0, g.dailyXp - q.awarded) else g.dailyXp)
        gs = g
        quests = quests.map { if (it.id == id) it.copy(done = false, awarded = 0, doneAt = 0, updatedAt = now()) else it }
        touch()
    }

    fun challengeDoneToday(id: String): Boolean = gs.challengeDay == todayStr() && id in gs.challengesDone

    fun completeChallenge(c: Game.Challenge): Boolean {
        val t = todayStr()
        if (challengeDoneToday(c.id)) return false
        val done = if (gs.challengeDay == t) gs.challengesDone + c.id else listOf(c.id)
        var g = gs.copy(challengeDay = t, challengesDone = done, challengeTotal = gs.challengeTotal + 1)
        g = addXp(g, c.xp)
        gs = g
        checkBadges()
        touch()
        return true
    }

    fun focusReward(minutes: Int): Int {
        val xp = (minutes / 2).coerceIn(5, 60)
        var g = gs.copy(focusSessions = gs.focusSessions + 1, totalMinutes = gs.totalMinutes + minutes)
        g = addXp(g, xp)
        gs = g
        checkBadges()
        touch()
        return xp
    }

    fun popReward() { if (rewards.isNotEmpty()) rewards = rewards.drop(1) }
}
