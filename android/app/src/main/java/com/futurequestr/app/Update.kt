package com.futurequestr.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class UpdateStage { Idle, Downloading, Verifying, Installing, Confirm, Done, Failed }

/** What the update screens show. Plain state, so the animation always matches what is really happening. */
object UpdateUi {
    var offer by mutableStateOf<Updater.Info?>(null)
    var dismissed by mutableStateOf("")
    var running by mutableStateOf(false)
    var stage by mutableStateOf(UpdateStage.Idle)
    var progress by mutableStateOf(0f)
    var error by mutableStateOf("")
    var info by mutableStateOf<Updater.Info?>(null)
    private var job: Job? = null

    fun fail(message: String) { error = message; stage = UpdateStage.Failed }

    /** Returns false when Android first needs the person to allow installs from this app. */
    fun start(ctx: Context, target: Updater.Info): Boolean {
        if (!Updater.canInstall(ctx)) { Updater.openInstallPermission(ctx); return false }
        val app = ctx.applicationContext
        info = target; error = ""; progress = 0f; stage = UpdateStage.Downloading; running = true
        job?.cancel()
        job = Sync.scope.launch {
            try {
                if (Installer.isDownloaded(app, target)) progress = 1f
                else Installer.download(app, target) { progress = it }
                stage = UpdateStage.Verifying
                delay(600)
                val err = Installer.verify(app, target)
                if (err != null) { Installer.file(app, target).delete(); fail(err); return@launch }
                stage = UpdateStage.Installing
                delay(500)
                val e2 = Installer.install(app, Installer.file(app, target))
                if (e2 != null) fail(e2)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("The download did not finish. Check your connection and try again.")
            }
        }
        return true
    }

    fun cancel() { job?.cancel(); running = false; stage = UpdateStage.Idle }
}

object Installer {
    fun file(c: Context, info: Updater.Info): File {
        val dir = File(c.cacheDir, "update"); dir.mkdirs()
        return File(dir, "FutureQuestr_" + info.version + ".apk")
    }

    /** The file only gets its real name once the whole download has finished, so existing means complete. */
    fun isDownloaded(c: Context, info: Updater.Info): Boolean {
        val f = file(c, info)
        return f.exists() && (info.size <= 0L || f.length() == info.size)
    }

    suspend fun download(c: Context, info: Updater.Info, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val out = file(c, info)
        if (isDownloaded(c, info)) { onProgress(1f); return@withContext out }
        out.parentFile?.listFiles()?.forEach { if (it.name != out.name) it.delete() }
        val tmp = File(out.parentFile, "download.part")
        var conn: HttpURLConnection? = null
        try {
            conn = URL(info.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            if (conn.responseCode != 200) throw IOException("The server answered " + conn.responseCode)
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.size
            conn.inputStream.use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) throw IOException("Could not save the file")
        out
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun certs(pi: PackageInfo): Set<String> {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo
            if (si == null) null else if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else pi.signatures
        return (sigs ?: emptyArray()).map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    /** Returns a plain message if the file must not be installed, or null when it looks right. */
    @Suppress("DEPRECATION")
    fun verify(c: Context, info: Updater.Info): String? {
        val f = file(c, info)
        if (!f.exists()) return "The downloaded file is missing."
        if (info.size > 0L && f.length() != info.size) return "The download was incomplete."
        if (info.sha256.isNotEmpty()) {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            if (!hex(md.digest()).equals(info.sha256, ignoreCase = true)) return "The file did not match its checksum, so it was not installed."
        }
        try {
            val pm = c.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = pm.getPackageArchiveInfo(f.path, flags) ?: return "That file is not a valid app."
            if (archive.packageName != c.packageName) return "That file is not FutureQuestr."
            val mine = certs(pm.getPackageInfo(c.packageName, flags))
            val theirs = certs(archive)
            if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()) {
                return "The update is signed with a different key, so it was not installed."
            }
        } catch (e: Exception) {
            // Android's own installer still checks this, so carry on.
        }
        return null
    }

    @Suppress("DEPRECATION")
    fun installerIsSelf(c: Context): Boolean = try {
        val pkg = if (Build.VERSION.SDK_INT >= 30) c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName
        else c.packageManager.getInstallerPackageName(c.packageName)
        pkg == c.packageName
    } catch (e: Exception) { false }

    /** Hands the file to Android's installer from inside the app. Returns an error message, or null once it is under way. */
    fun install(c: Context, f: File): String? {
        return try {
            val installer = c.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                f.inputStream().use { input ->
                    s.openWrite("update.apk", 0, f.length()).use { o ->
                        input.copyTo(o)
                        s.fsync(o)
                    }
                }
                val intent = Intent(c, InstallReceiver::class.java).setPackage(c.packageName)
                val pending = PendingIntent.getBroadcast(c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(pending.intentSender)
            }
            null
        } catch (e: Exception) {
            e.message ?: "Could not start the install."
        }
    }
}

/** Android reports how the install went (or that it needs a tap) to this receiver. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) { UpdateUi.fail("Android did not show its confirmation screen."); return }
                UpdateUi.stage = UpdateStage.Confirm
                if (Store.foreground) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { c.startActivity(confirm) } catch (e: Exception) { notifyConfirm(c, confirm) }
                } else notifyConfirm(c, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateUi.stage = UpdateStage.Done
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateUi.fail("The install was cancelled.")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateUi.fail("Android blocked the install. Allow installs from FutureQuestr in settings.")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateUi.fail("The update does not match the installed app, so Android refused it.")
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateUi.fail("This update does not work on your device.")
            PackageInstaller.STATUS_FAILURE_INVALID -> UpdateUi.fail("The update file is not valid.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateUi.fail("There is not enough storage space for the update.")
            else -> UpdateUi.fail("The install did not finish. " + msg)
        }
    }

    private fun notifyConfirm(c: Context, confirm: Intent) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val pi = PendingIntent.getActivity(c, 9, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, "updates").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("FutureQuestr update").setContentText("Tap to finish installing the update.")
            .setAutoCancel(true).setContentIntent(pi).build()
        try { NotificationManagerCompat.from(c).notify(1005, n) } catch (e: SecurityException) { }
    }
}

/** After an update finishes the app is closed by Android, so tell the person it is done. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        try {
            Store.init(c)
            Notifs.post(c, "updates", 1004, "FutureQuestr updated", "You are now on version " + BuildConfig.VERSION_NAME + ". Tap to open.")
        } catch (e: Exception) { }
    }
}

/** Runs from the 30 minute background check. */
object BackgroundUpdate {
    private fun canDownloadNow(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (cm.isActiveNetworkMetered) return false
        val bat = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return charging || (level * 100 / scale.coerceAtLeast(1)) >= 25
    }

    suspend fun handle(c: Context, info: Updater.Info) {
        val sp = Store.prefs
        if (Store.updateMode == 0) {
            if (sp.getString("updNotified", "") != info.version) {
                Notifs.post(c, "updates", 1002, "FutureQuestr update available", "Version " + info.version + " is available. Open the app to install it.")
                sp.edit().putString("updNotified", info.version).apply()
            }
            return
        }
        if (!Installer.isDownloaded(c, info)) {
            if (!canDownloadNow(c)) return
            try { Installer.download(c, info) { } } catch (e: Exception) { return }
        }
        val err = Installer.verify(c, info)
        if (err != null) { Installer.file(c, info).delete(); return }
        val canSilent = Store.updateMode == 2 && Build.VERSION.SDK_INT >= 31 && Updater.canInstall(c) &&
            Installer.installerIsSelf(c) && !Store.foreground && !Focus.active(c)
        if (canSilent && Installer.install(c, Installer.file(c, info)) == null) return
        if (sp.getString("updReadyNotified", "") != info.version) {
            Notifs.post(c, "updates", 1002, "FutureQuestr update ready", "Version " + info.version + " is downloaded. Tap to install it.")
            sp.edit().putString("updReadyNotified", info.version).apply()
        }
    }
}

// ---------------------------------------------------------------- screens

private fun updateTitle(s: UpdateStage) = when (s) {
    UpdateStage.Downloading -> "Downloading the update"
    UpdateStage.Verifying -> "Checking the update"
    UpdateStage.Installing -> "Installing"
    UpdateStage.Confirm -> "One last tap"
    UpdateStage.Done -> "Updated"
    UpdateStage.Failed -> "The update did not finish"
    UpdateStage.Idle -> ""
}

private fun updateLine(s: UpdateStage, progress: Float, error: String) = when (s) {
    UpdateStage.Downloading -> "${(progress * 100).toInt()}%"
    UpdateStage.Verifying -> "Making sure the file is genuine."
    UpdateStage.Installing -> "FutureQuestr closes when this finishes. Open it again to carry on."
    UpdateStage.Confirm -> "Android needs your OK. Tap Install on the next screen."
    UpdateStage.Done -> "All done."
    UpdateStage.Failed -> error
    UpdateStage.Idle -> ""
}

@Composable
fun UpdateIcon(progress: Float, spinning: Boolean, done: Boolean) {
    val accent = accentColor()
    val track = MaterialTheme.colorScheme.outline
    val inf = rememberInfiniteTransition(label = "icon")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "spin")
    val breathe by inf.animateFloat(1f, 1.05f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val shown by animateFloatAsState(if (done) 1f else progress, tween(350), label = "ring")
    Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 5.dp.toPx()
            val topLeft = Offset(sw, sw)
            val arcSize = Size(size.width - sw * 2, size.height - sw * 2)
            drawArc(track, -90f, 360f, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            if (spinning && !done) drawArc(accent, spin - 90f, 110f, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            else drawArc(accent, -90f, 360f * shown, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Box(
            Modifier.size(112.dp).scale(breathe)
                .background(Brush.linearGradient(listOf(Color(0xFF4A5FDC), Color(0xFF1A2060))), RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun UpdateOverlay() {
    if (!UpdateUi.running) return
    val stage = UpdateUi.stage
    val target = UpdateUi.info
    val ctx = LocalContext.current
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(32.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                UpdateIcon(UpdateUi.progress, stage != UpdateStage.Downloading && stage != UpdateStage.Failed, stage == UpdateStage.Done)
                Spacer(Modifier.height(28.dp))
                Crossfade(targetState = stage, label = "updateText") { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(updateTitle(s), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(updateLine(s, UpdateUi.progress, UpdateUi.error), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                if (target != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Version ${BuildConfig.VERSION_NAME} to ${target.version}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(28.dp))
                when (stage) {
                    UpdateStage.Downloading -> FQOutlinedButton(onClick = { UpdateUi.cancel() }) { Text("Cancel") }
                    UpdateStage.Failed -> Row {
                        FQButton(onClick = { if (target != null) UpdateUi.start(ctx, target) }) { Text("Try again") }
                        Spacer(Modifier.width(8.dp))
                        FQOutlinedButton(onClick = { UpdateUi.cancel() }) { Text("Close") }
                    }
                    else -> { }
                }
            }
        }
    }
}

/** A ring that draws itself, then a tick. */
@Composable
fun RingCheck(boxSize: Dp, color: Color) {
    val ring = remember { Animatable(0f) }
    val tick = remember { Animatable(0f) }
    LaunchedEffect(Unit) { ring.animateTo(1f, tween(700)); tick.animateTo(1f, tween(450)) }
    Canvas(Modifier.size(boxSize)) {
        val sw = 4.dp.toPx()
        drawArc(color, -90f, 360f * ring.value, false, Offset(sw, sw), Size(size.width - sw * 2, size.height - sw * 2), style = Stroke(sw, cap = StrokeCap.Round))
        val a = Offset(size.width * 0.30f, size.height * 0.52f)
        val b = Offset(size.width * 0.45f, size.height * 0.67f)
        val c = Offset(size.width * 0.72f, size.height * 0.36f)
        val t = tick.value
        val p1 = (t / 0.4f).coerceIn(0f, 1f)
        val p2 = ((t - 0.4f) / 0.6f).coerceIn(0f, 1f)
        if (p1 > 0f) drawLine(color, a, a + (b - a) * p1, strokeWidth = sw, cap = StrokeCap.Round)
        if (p2 > 0f) drawLine(color, b, b + (c - b) * p2, strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** Shown once the first time the app opens after an update. */
@Composable
fun UpdatedDialog(onDone: () -> Unit) {
    val note = if (Store.prefs.getString("updVer", "") == BuildConfig.VERSION_NAME) (Store.prefs.getString("updNote", "") ?: "") else ""
    Dialog(onDismissRequest = onDone) {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                RingCheck(72.dp, accentColor())
                Spacer(Modifier.height(16.dp))
                Text("Updated", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("You are now on version ${BuildConfig.VERSION_NAME}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (note.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(note.take(400), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                FQButton(onClick = onDone) { Text("Continue") }
            }
        }
    }
}
