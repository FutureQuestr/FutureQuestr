package com.futurequestr.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- helpers

fun dayLabel(day: String): String {
    val t = LocalDate.parse(Store.todayStr())
    val d = LocalDate.parse(day)
    return when (d) {
        t -> "Today"
        t.plusDays(1) -> "Tomorrow"
        t.minusDays(1) -> "Yesterday"
        else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.dayOfMonth + " " +
            d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    }
}

fun matches(q: Quest, query: String): Boolean {
    if (query.isBlank()) return true
    val s = query.trim().lowercase()
    val d = LocalDate.parse(q.day)
    return q.title.lowercase().contains(s) || q.day.contains(s) || fmtTime24(q.startMin).contains(s) || fmtTime12(q.startMin).lowercase().contains(s) ||
        d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase().contains(s) ||
        d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase().contains(s) || q.category.contains(s)
}

fun shareTimetable(ctx: Context) {
    val today = Store.todayStr()
    val end = LocalDate.parse(today).plusDays(6).toString()
    val list = Store.visibleQuests().filter { it.day in today..end }.sortedWith(compareBy({ it.day }, { it.startMin }))
    val txt = buildString {
        appendLine("My FutureQuestr timetable")
        var last = ""
        for (q in list) {
            if (q.day != last) { appendLine(); appendLine(dayLabel(q.day) + ":"); last = q.day }
            appendLine("  " + fmtTime(q.startMin) + " to " + fmtTime(q.startMin + q.durationMin) + "  " + q.title)
        }
        if (list.isEmpty()) appendLine("(no Quests yet)")
    }
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt)
    ctx.startActivity(Intent.createChooser(i, "Share timetable"))
}

@Composable
fun Pill(text: String, selected: Boolean, icon: Ico? = null, onClick: () -> Unit) {
    val accent = accentColor()
    val shape = RoundedCornerShape(8.dp)
    val fg = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.padding(end = 6.dp).clip(shape)
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) accent else MaterialTheme.colorScheme.outline, shape)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { FQIcon(icon, fg, 16.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun XpHeader() {
    val g = Store.gs
    val p by animateFloatAsState((g.xpInLevel.toFloat() / g.target.coerceAtLeast(1)).coerceIn(0f, 1f), tween(600), label = "xp")
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Level ${g.level}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text("${g.xpInLevel} / ${g.target} XP", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = accentColor(), trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Spacer(Modifier.width(14.dp))
            FQIcon(Ico.Flame, accentColor(), 18.dp)
            Spacer(Modifier.width(4.dp))
            Text("${Store.effectiveStreak()}", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { Store.applyMuted(!Store.muted); Sfx.play("tap") }, contentAlignment = Alignment.Center) {
                FQIcon(if (Store.muted) Ico.SoundOff else Ico.SoundOn, MaterialTheme.colorScheme.onSurfaceVariant, 20.dp)
            }
        }
    }
}

// ---------------------------------------------------------------- root of the signed-in app

@Composable
fun HomeScreen(onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(0) }
    val resumeTick = UiBus.resume.value
    val toast: (String) -> Unit = { m -> scope.launch { snack.showSnackbar(m) } }

    LaunchedEffect(resumeTick) {
        withContext(Dispatchers.IO) {
            try { Updater.check() } catch (e: Exception) { }
            try { Sync.run() } catch (e: Exception) { }
        }
        UpdateUi.offer = Updater.pending()
    }

    LaunchedEffect(Unit) {
        while (true) {
            Focus.tick(ctx)
            val m = Focus.consumePending(ctx)
            if (m > 0) {
                val xp = Store.focusReward(m)
                Sfx.play("focus_end"); Sfx.buzz(60)
                toast("Focus session complete! +$xp XP")
            }
            delay(1000)
        }
    }

    val reward = Store.rewards.firstOrNull()
    if (reward != null) RewardDialog(reward)
    Sync.conflict?.let { ConflictDialog(it) }
    val upd = UpdateUi.offer
    if (upd != null && UpdateUi.dismissed != upd.version && !UpdateUi.running) {
        val ready = Installer.isDownloaded(ctx, upd)
        AlertDialog(
            onDismissRequest = { UpdateUi.dismissed = upd.version },
            title = { Text(if (ready) "Update ready" else "Update available") },
            text = {
                Text("FutureQuestr ${upd.version} " + (if (ready) "is downloaded and ready to install." else "is available.") +
                    " You have ${BuildConfig.VERSION_NAME}." + (if (upd.note.isNotBlank()) "\n\n" + upd.note.take(300) else ""))
            },
            confirmButton = {
                FQButton(onClick = {
                    UpdateUi.dismissed = upd.version
                    if (!UpdateUi.start(ctx, upd)) toast("Allow installs from FutureQuestr, then tap Update now again.")
                }) { Text("Update now") }
            },
            dismissButton = { TextButton(onClick = { UpdateUi.dismissed = upd.version }) { Text("Later") } }
        )
    }
    UpdateOverlay()

    var showUpdated by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val seen = Store.prefs.getString("lastSeenVersion", "") ?: ""
        val cameFromUpdate = seen.isEmpty() && Store.prefs.getString("updVer", "") == BuildConfig.VERSION_NAME
        if ((seen.isNotEmpty() && seen != BuildConfig.VERSION_NAME) || cameFromUpdate) showUpdated = true
        else Store.prefs.edit().putString("lastSeenVersion", BuildConfig.VERSION_NAME).apply()
    }
    if (showUpdated) {
        UpdatedDialog { showUpdated = false; Store.prefs.edit().putString("lastSeenVersion", BuildConfig.VERSION_NAME).apply() }
    }

    val tabs = listOf("Quests" to Ico.Quests, "Plan" to Ico.Plan, "Progress" to Ico.Progress, "Focus" to Ico.Focus, "Account" to Ico.Account)
    Scaffold(
        snackbarHost = {
            SnackbarHost(snack) { data ->
                val shape = RoundedCornerShape(10.dp)
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape),
                    shape = shape,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = accentColor()
                )
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i; Sfx.play("tap") },
                        icon = { FQIcon(t.second, LocalContentColor.current, 22.dp) }, label = { Text(t.first, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { pad ->
        val mod = Modifier.fillMaxSize().padding(pad)
        when (tab) {
            0 -> QuestsTab(mod, toast, snack)
            1 -> PlanTab(mod, toast)
            2 -> ProgressTab(mod, toast)
            3 -> FocusTab(mod, toast)
            else -> AccountTab(mod, onSignedOut, toast)
        }
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun RewardDialog(r: Reward) {
    var shown by remember(r) { mutableStateOf(false) }
    val sc by animateFloatAsState(if (shown) 1f else 0.88f, spring(dampingRatio = 0.65f, stiffness = 300f), label = "pop")
    LaunchedEffect(r) {
        shown = true
        when (r) {
            is Reward.Level -> { Sfx.play("levelup"); Sfx.buzz(120) }
            is Reward.BadgeEarned -> { Sfx.play("badge"); Sfx.buzz(60) }
        }
    }
    AlertDialog(
        onDismissRequest = { Store.popReward() },
        confirmButton = { FQButton(onClick = { Store.popReward() }) { Text("Continue") } },
        text = {
            Column(Modifier.fillMaxWidth().scale(sc), horizontalAlignment = Alignment.CenterHorizontally) {
                when (r) {
                    is Reward.Level -> {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(accentColor()), contentAlignment = Alignment.Center) {
                            FQIcon(Ico.Level, Color(0xFF05241A), 38.dp)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Level ${r.level}", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("You reached a new level.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Reward.BadgeEarned -> {
                        BadgeGlyph(r.badge.icon, true)
                        Spacer(Modifier.height(16.dp))
                        Text("Badge earned", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(r.badge.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(r.badge.desc, textAlign = TextAlign.Center, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    )
}

@Composable
fun ConflictDialog(c: Sync.Conflict) {
    val fmt = java.text.DateFormat.getDateTimeInstance()
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Which timetable should we keep?") },
        text = {
            Column {
                Text("This phone and your account in the cloud both changed since they last matched. Pick the one to keep. The other will be replaced.")
                Spacer(Modifier.height(12.dp))
                Text("This phone", fontWeight = FontWeight.SemiBold)
                Text("${c.localQuests} Quests, level ${c.localLevel}")
                Spacer(Modifier.height(8.dp))
                Text("Your account", fontWeight = FontWeight.SemiBold)
                Text("${c.serverQuests} Quests, level ${c.serverLevel}")
                if (c.serverUpdated > 0) Text("Last saved ${fmt.format(Date(c.serverUpdated))}", fontSize = 12.sp)
            }
        },
        confirmButton = { FQButton(onClick = { Sync.keepLocal() }) { Text("Keep this phone") } },
        dismissButton = { FQOutlinedButton(onClick = { Sync.keepServer() }) { Text("Keep account") } }
    )
}

@Composable
fun QuestDialog(initial: Quest?, onDismiss: () -> Unit, onSave: (Quest, Boolean) -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var day by remember { mutableStateOf(initial?.day ?: Store.todayStr()) }
    var start by remember { mutableStateOf(initial?.startMin ?: 17 * 60) }
    var dur by remember { mutableStateOf((initial?.durationMin ?: 30).toString()) }
    var cat by remember { mutableStateOf(initial?.category ?: "study") }
    var err by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New Quest" else "Edit Quest") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it.take(60) }, label = { Text("What's the Quest?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row {
                    FQOutlinedButton(onClick = {
                        val d = LocalDate.parse(day)
                        DatePickerDialog(ctx, { _, y, m, dd -> day = LocalDate.of(y, m + 1, dd).toString() }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    }) { Text(dayLabel(day)) }
                    Spacer(Modifier.width(8.dp))
                    FQOutlinedButton(onClick = {
                        TimePickerDialog(ctx, { _, h, m -> start = h * 60 + m }, start / 60, start % 60, Store.use24h).show()
                    }) { Text(fmtTime(start)) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(dur, { dur = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("Minutes (10 to 240)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Game.categories.forEach { c -> Pill(Game.label(c), cat == c, Game.icon(c)) { cat = c } }
                }
                if (err.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            FQButton(onClick = {
                val d = dur.toIntOrNull() ?: 0
                when {
                    title.isBlank() -> err = "Give your Quest a name."
                    d < 10 || d > 240 -> err = "Quests are 10 to 240 minutes."
                    start + d > 24 * 60 -> err = "That Quest runs past midnight. Shorten it or start earlier."
                    Store.minutesOn(day, initial?.id) + d > 600 -> err = "That's over 10 hours of Quests for one day. Take a break."
                    else -> {
                        val q = initial?.copy(title = title.trim(), day = day, startMin = start, durationMin = d, category = cat)
                            ?: Quest(java.util.UUID.randomUUID().toString(), title.trim(), day, start, d, cat, Game.standardXp(d))
                        onSave(q, initial == null)
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// ---------------------------------------------------------------- tabs

@Composable
fun QuestsTab(mod: Modifier, toast: (String) -> Unit, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(0) }
    var cat by remember { mutableStateOf("all") }
    var hideDone by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Quest?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun doComplete(q: Quest) {
        val o = Store.complete(q.id)
        if (!o.ok) { Sfx.play("soft"); toast(o.message.ifEmpty { "Couldn't complete that Quest." }); return }
        if (o.xp > 0) {
            Sfx.ding(); Sfx.buzz(35)
            val st = Store.effectiveStreak()
            scope.launch { delay(180); Sfx.play("tick"); delay(140); Sfx.play("streak", 1f + 0.06f * minOf(st, 10)) }
            toast("+${o.xp} XP" + (if (o.lucky) "  (lucky bonus)" else ""))
        } else toast(o.message.ifEmpty { "Quest complete." })
    }

    fun rateAsync(q: Quest) {
        Sync.scope.launch {
            val e = Net.rate(q.title, q.durationMin)
            if (e != null) Store.applyRating(q.id, e)
        }
    }

    val today = Store.todayStr()
    val weekEnd = LocalDate.parse(today).plusDays(6).toString()
    val list = Store.visibleQuests().filter { q ->
        (range == 0 || (range == 1 && q.day == today) || (range == 2 && q.day >= today && q.day <= weekEnd)) &&
            (cat == "all" || q.category == cat) && (!hideDone || !q.done) && matches(q, query)
    }.sortedWith(compareBy({ it.day }, { it.startMin }))
    val grouped = list.groupBy { it.day }

    Column(mod.padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(8.dp))
        XpHeader()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search by name, day or time") },
                singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            FQButton(onClick = { adding = true; Sfx.play("tap") }) { FQIcon(Ico.Plus, LocalContentColor.current, 20.dp) }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Pill("All", range == 0) { range = 0 }
            Pill("Today", range == 1) { range = 1 }
            Pill("This week", range == 2) { range = 2 }
            Pill("Hide done", hideDone) { hideDone = !hideDone }
            Pill("Share", false, Ico.Share) { shareTimetable(ctx) }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Pill("Any type", cat == "all") { cat = "all" }
            Game.categories.forEach { c -> Pill(Game.label(c), cat == c, Game.icon(c)) { cat = c } }
        }
        Spacer(Modifier.height(4.dp))

        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                FQIcon(Ico.Quests, MaterialTheme.colorScheme.outline, 44.dp)
                Spacer(Modifier.height(8.dp))
                Text(if (Store.visibleQuests().isEmpty()) "No Quests yet. Add one with the plus button, or let the AI planner build your week." else "No Quests match those filters.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (day, qs) ->
                    item(key = "h$day") {
                        Text(dayLabel(day).uppercase(), fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    }
                    items(qs, key = { it.id }) { q ->
                        Panel(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { editing = q }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CatTile(q.category, 38.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f).alpha(if (q.done) 0.55f else 1f)) {
                                    Text(q.title, fontWeight = FontWeight.SemiBold,
                                        textDecoration = if (q.done) TextDecoration.LineThrough else null)
                                    Text(fmtTime(q.startMin) + " to " + fmtTime(q.startMin + q.durationMin) + ",  ${q.durationMin} min,  ${q.xp} XP",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (q.done) {
                                    TextButton(onClick = { Store.uncomplete(q.id); Sfx.play("soft") }) { Text("Undo") }
                                } else {
                                    FQButton(onClick = { doComplete(q) }) { Text("Done") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        QuestDialog(null, { adding = false }, { q, isNew ->
            val saved = Store.saveQuest(q, isNew); adding = false; Sfx.play("tap"); rateAsync(saved)
        }, null)
    }
    val e = editing
    if (e != null) {
        QuestDialog(e, { editing = null }, { q, isNew ->
            val saved = Store.saveQuest(q, isNew); editing = null; Sfx.play("tap")
            if (!saved.rated && !saved.done) rateAsync(saved)
        }, {
            Store.deleteQuest(e.id); editing = null
            scope.launch {
                val r = snack.showSnackbar("Quest deleted", "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) Store.undoDelete(e.id)
            }
        })
    }
}

@Composable
fun PlanTab(mod: Modifier, toast: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<List<Quest>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }

    Column(mod.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("AI planner", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Tell me about your week: subjects, deadlines, goals and when you're free. I'll turn it into Quests.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(text, { text = it.take(800) }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            label = { Text("e.g. Maths test on Friday, free after 4pm, want to exercise 3 times") })
        Spacer(Modifier.height(10.dp))
        FQButton(enabled = !busy && text.isNotBlank(), onClick = {
            busy = true; note = ""; detail = ""
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    try { Net.plan(text) } catch (e: Exception) { Net.PlanResult(null, error = "unavailable", detail = (e.message ?: "no connection").replace("-", " ").take(140)) }
                }
                busy = false
                if (r.quests != null && r.quests.isNotEmpty()) {
                    result = r.quests; Sfx.play("badge")
                    note = "Planned by " + (when (r.provider) { "groq" -> "Groq"; "github" -> "GitHub Models"; "openrouter" -> "OpenRouter"; else -> "AI" })
                } else {
                    result = Net.templatePlan(); Sfx.play("soft"); detail = r.detail
                    note = when (r.error) {
                        "limit" -> "You have used today's AI planning. Here is a ready made starter timetable instead."
                        "signin" -> "Please sign out and back in from the Account tab. Here is a starter timetable for now."
                        else -> "The AI planner could not answer just now. Here is a ready made starter timetable."
                    }
                }
            }
        }) { Text(if (busy) "Planning..." else "Plan my week") }

        if (busy) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }

        if (result.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(note, color = accentColor(), fontWeight = FontWeight.SemiBold)
            if (detail.isNotEmpty()) Text("Details: " + detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            result.sortedWith(compareBy({ it.day }, { it.startMin })).groupBy { it.day }.forEach { (day, qs) ->
                Text(dayLabel(day), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                qs.forEach { q ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        FQIcon(Game.icon(q.category), MaterialTheme.colorScheme.onSurfaceVariant, 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(fmtTime(q.startMin) + "   " + q.title + "  (${q.durationMin} min)", fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row {
                FQButton(onClick = {
                    Store.addMany(result); Sfx.play("challenge"); Sfx.buzz(40)
                    toast("Added ${result.size} Quests"); result = emptyList(); text = ""
                }) { Text("Add all ${result.size} Quests") }
                Spacer(Modifier.width(8.dp))
                FQOutlinedButton(onClick = { result = emptyList() }) { Text("Discard") }
            }
        }
    }
}

@Composable
fun ProgressTab(mod: Modifier, toast: (String) -> Unit) {
    val today = Store.todayStr()
    val ch = Game.todaysChallenges(today)
    val weekAgo = Store.now() - 7L * 24 * 3600 * 1000
    val mins = Game.categories.associateWith { c ->
        Store.visibleQuests().filter { it.done && it.doneAt >= weekAgo && it.category == c }.sumOf { it.durationMin }
    }
    val maxMin = (mins.values.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(mod.verticalScroll(rememberScrollState()).padding(16.dp)) {
        XpHeader()
        Spacer(Modifier.height(16.dp))
        Text("Today's challenges", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        ch.forEach { c ->
            val done = Store.challengeDoneToday(c.id)
            Panel(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FQIcon(Game.icon(c.cat), accentColor(), 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(c.text, fontSize = 14.sp)
                        }
                        Text("+${c.xp} XP", fontSize = 12.sp, color = accentColor())
                    }
                    FQButton(enabled = !done, onClick = {
                        if (Store.completeChallenge(c)) { Sfx.play("challenge"); Sfx.buzz(40); toast("Challenge complete, +${c.xp} XP") }
                    }) { Text(if (done) "Completed" else "Complete") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Your week", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("Minutes completed in the last 7 days", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Game.categories.forEach { c ->
            val m = mins[c] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Box(Modifier.width(30.dp)) { FQIcon(Game.icon(c), MaterialTheme.colorScheme.onSurfaceVariant, 18.dp) }
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(m.toFloat() / maxMin).fillMaxHeight().background(accentColor()))
                }
                Text("  $m", fontSize = 12.sp, modifier = Modifier.width(48.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Badges (${Store.gs.badges.size}/${Game.badges.size})", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Game.badges.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { b ->
                    val on = b.id in Store.gs.badges
                    Column(Modifier.weight(1f).padding(4.dp).alpha(if (on) 1f else 0.55f), horizontalAlignment = Alignment.CenterHorizontally) {
                        BadgeGlyph(b.icon, on)
                        Text(b.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text(b.desc, fontSize = 10.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun FocusTab(mod: Modifier, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    var minutes by remember { mutableStateOf(45) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val resume = UiBus.resume.value
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val end = Focus.endTime(ctx)
    val active = end > now
    val a11y = Perms.a11yOk(ctx)

    Column(mod.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Focus Mode", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Other apps get an 'App locked by FutureQuestr' screen and notifications go quiet. Calls and messages still work. Normal mode (reminders only) is always on.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (active) {
            val left = ((end - now) / 1000).coerceAtLeast(0)
            Text("%02d:%02d".format(left / 60, left % 60), fontSize = 56.sp, fontWeight = FontWeight.SemiBold, color = accentColor())
            Text("Stay with it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            FQOutlinedButton(onClick = { Focus.cancel(ctx); Sfx.play("soft"); toast("Focus session ended early. No reward this time.") }) { Text("End early") }
        } else {
            Row { listOf(25, 45, 60, 90).forEach { m -> Pill("$m min", minutes == m) { minutes = m } } }
            Spacer(Modifier.height(20.dp))
            FQButton(onClick = { Focus.start(ctx, minutes); Sfx.play("focus_start"); Sfx.buzz(50) }) { Text("Start Focus session") }
            Spacer(Modifier.height(6.dp))
            Text("Finish the whole session for bonus XP.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!a11y) {
            Spacer(Modifier.height(24.dp))
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Finish setup", fontWeight = FontWeight.Bold)
                    Text("The lock screen needs the FutureQuestr accessibility switch turned on.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    FQButton(onClick = { Perms.openAccessibility(ctx) }) { Text("Open settings") }
                }
            }
        }
        if (resume < 0) Text("")
    }
}

@Composable
fun AccountTab(mod: Modifier, onSignedOut: () -> Unit, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dlg by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Store.BackupInfo?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(Store.exportBackup(BuildConfig.VERSION_NAME).toByteArray(Charsets.UTF_8)) }
                    true
                } catch (e: Exception) { false }
            }
            toast(if (ok) "Backup saved." else "Could not save the backup.")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: "" }
                pending = Store.readBackup(text)
            } catch (e: Exception) {
                toast(e.message ?: "That file is not a FutureQuestr backup.")
            }
        }
    }

    fun serverDelete(path: String, onOk: () -> Unit) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { try { Net.api("DELETE", path).first == 200 } catch (e: Exception) { false } }
            if (ok) onOk() else toast("Couldn't reach the server. Connect to the internet and try again.")
        }
    }

    Column(mod.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Account", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Signed in as ${Store.login} (GitHub)")
        Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Sync: " + when (Sync.status) { "pushed", "ok", "pulled" -> "up to date"; "offline" -> "offline (saved on this phone)"; "conflict" -> "needs your choice"; "" -> "waiting"; else -> Sync.status },
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        FQButton(onClick = {
            scope.launch {
                val i = withContext(Dispatchers.IO) { try { Updater.check(true) } catch (e: Exception) { null } }
                if (i == null) toast("You are on the latest version.") else { UpdateUi.dismissed = ""; UpdateUi.offer = i }
            }
        }) { Text("Check for updates") }
        Spacer(Modifier.height(8.dp))
        Text("Time format", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row {
            Pill("12 hour", !Store.use24h) { Store.applyUse24h(false) }
            Pill("24 hour", Store.use24h) { Store.applyUse24h(true) }
        }
        Spacer(Modifier.height(16.dp))
        Text("Appearance", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row {
            Pill("System", Store.themeMode == 0) { Store.applyThemeMode(0) }
            Pill("Light", Store.themeMode == 1) { Store.applyThemeMode(1) }
            Pill("Dark", Store.themeMode == 2) { Store.applyThemeMode(2) }
        }
        Spacer(Modifier.height(16.dp))
        Text("Updates", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Pill("Ask first", Store.updateMode == 0) { Store.applyUpdateMode(0) }
            Pill("Download in background", Store.updateMode == 1) { Store.applyUpdateMode(1) }
            Pill("Install automatically", Store.updateMode == 2) { Store.applyUpdateMode(2) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (Store.updateMode) {
                0 -> "Tells you when an update is out. Nothing downloads until you say so."
                1 -> "Downloads updates on WiFi when your battery is fine, then asks you to install."
                else -> "Downloads on WiFi and installs by itself when Android allows it. Otherwise it asks you."
            },
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text("Backup", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Saves your Quests, XP, level, streak, badges and settings to a file that you keep. It never includes your sign in.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row {
            FQOutlinedButton(onClick = { exportLauncher.launch("FutureQuestr backup " + Store.todayStr().replace("-", "") + ".json") }) { Text("Export backup") }
            Spacer(Modifier.width(8.dp))
            FQOutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import backup") }
        }
        Spacer(Modifier.height(16.dp))
        FQOutlinedButton(onClick = { Store.signOut(); onSignedOut() }) { Text("Sign out") }
        Spacer(Modifier.height(8.dp))
        FQOutlinedButton(onClick = { dlg = "data" }) { Text("Delete my data") }
        Spacer(Modifier.height(8.dp))
        FQOutlinedButton(onClick = { dlg = "account" }) { Text("Delete my account", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { dlg = "privacy" }) { Text("Privacy notice") }
    }

    val pb = pending
    if (pb != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore this backup?") },
            text = {
                Text("Made " + java.text.DateFormat.getDateTimeInstance().format(Date(pb.createdAt)) +
                    ". It has ${pb.quests} Quests and is at level ${pb.level}.\n\n" +
                    "Replace swaps everything on this device for the backup. Merge keeps what is here and adds what is missing.")
            },
            confirmButton = { FQButton(onClick = { Store.applyBackup(pb, false); pending = null; toast("Backup restored.") }) { Text("Replace") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { Store.applyBackup(pb, true); pending = null; toast("Backup merged.") }) { Text("Merge") }
                    TextButton(onClick = { pending = null }) { Text("Cancel") }
                }
            }
        )
    }

    when (dlg) {
        "data" -> AlertDialog(
            onDismissRequest = { dlg = "" }, title = { Text("Delete my data?") },
            text = { Text("This wipes your Quests, XP, level, streak and badges from this phone and from the cloud. You stay signed in and start fresh. This can't be undone.") },
            confirmButton = { FQButton(onClick = { dlg = ""; serverDelete("/data") { Store.resetData(); toast("Your data was deleted.") } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("Cancel") } })
        "account" -> AlertDialog(
            onDismissRequest = { dlg = "" }, title = { Text("Delete my account?") },
            text = { Text("This deletes everything above plus your FutureQuestr account record, and signs you out. Your GitHub account isn't touched, but you can also remove FutureQuestr's access in your GitHub settings under Applications. This can't be undone.") },
            confirmButton = { FQButton(onClick = { dlg = ""; serverDelete("/account") { Store.resetData(); Store.signOut(); onSignedOut() } }) { Text("Delete account") } },
            dismissButton = { TextButton(onClick = { dlg = "" }) { Text("Cancel") } })
        "privacy" -> AlertDialog(
            onDismissRequest = { dlg = "" }, title = { Text("Privacy notice") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("FutureQuestr keeps your Quests, XP, level, streak and badges on your phone. While you're signed in, a copy is stored on our server so you can use your timetable on other devices.\n\n" +
                        "When you ask the AI to plan your week or rate a Quest, the text you wrote is sent to AI providers (Groq, GitHub Models, OpenRouter) to produce the result. Please don't put personal details in Quest titles or planner text.\n\n" +
                        "We use your GitHub account ID and username only to sign you in and keep your data separate from other people's. We don't store your birth year, only whether you're 16 or over.\n\n" +
                        "Focus Mode uses an Android accessibility service that only checks which app is open. It does not read your screen, messages or passwords.\n\n" +
                        "You can delete your data, or your whole account, from this screen at any time.")
                }
            },
            confirmButton = { FQButton(onClick = { dlg = "" }) { Text("Close") } })
    }
}
