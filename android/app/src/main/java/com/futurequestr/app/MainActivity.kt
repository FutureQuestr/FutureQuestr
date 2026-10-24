package com.futurequestr.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

object UiBus { val resume = mutableStateOf(0) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Store.init(this)
        Sfx.init(this)
        setContent {
            FQTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }

    override fun onStart() { super.onStart(); Store.foreground = true }
    override fun onStop() { Store.foreground = false; super.onStop() }

    override fun onResume() {
        super.onResume()
        Store.init(this)
        Store.prefs.edit().putInt("ignored", 0).putString("lastOpenDay", LocalDate.now().toString()).apply()
        Focus.tick(this)
        UiBus.resume.value = UiBus.resume.value + 1
    }
}

enum class Stage { Loading, Age, Blocked, SignIn, Perms, Home }

fun nextStage(): Stage = when {
    Store.underage -> Stage.Blocked
    !Store.ageOk -> Stage.Age
    Store.token.isEmpty() -> Stage.SignIn
    !Store.permsDone -> Stage.Perms
    else -> Stage.Home
}

@Composable
fun Root() {
    val dark = isDarkTheme()
    var stage by remember { mutableStateOf(Stage.Loading) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        val t0 = SystemClock.elapsedRealtime()
        val work = async(Dispatchers.IO) {
            progress = 0.2f
            Net.syncClock()
            progress = 0.6f
            if (Store.token.isNotEmpty()) Sync.run()
            progress = 0.9f
        }
        withTimeoutOrNull(8000L) { work.await() }
        progress = 1f
        val el = SystemClock.elapsedRealtime() - t0
        if (el < 1600L) delay(1600L - el)
        delay(350L)
        stage = nextStage()
    }

    Crossfade(targetState = stage, animationSpec = tween(450), label = "stage") { s ->
        when (s) {
            Stage.Loading -> LoadingScreen(dark, progress)
            Stage.Age -> AgeScreen { stage = nextStage() }
            Stage.Blocked -> BlockedScreen()
            Stage.SignIn -> SignInScreen { stage = nextStage() }
            Stage.Perms -> PermsScreen { stage = nextStage() }
            Stage.Home -> HomeScreen(onSignedOut = { stage = Stage.SignIn })
        }
    }
}

@Composable
fun LoadingScreen(dark: Boolean, progress: Float) {
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(900)) }
    val p by animateFloatAsState(progress, tween(500), label = "bar")
    val bg = MaterialTheme.colorScheme.background
    val track = if (dark) Color(0xFF262D6B) else Color(0xFFDDE2F5)
    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth(0.92f).aspectRatio(2220f / 600f).alpha(fade.value)) {
            Image(
                painter = painterResource(if (dark) R.drawable.logo_dark_nobar else R.drawable.logo_light_nobar),
                contentDescription = "FutureQuestr", modifier = Modifier.fillMaxSize()
            )
            Canvas(Modifier.fillMaxSize()) {
                val x0 = size.width * (612f / 2220f)
                val x1 = size.width * (2082f / 2220f)
                val cy = size.height * (444f / 600f)
                val h = size.height * (44f / 600f)
                drawRoundRect(track, Offset(x0, cy - h / 2), Size(x1 - x0, h), CornerRadius(h / 2))
                val w = (x1 - x0) * p.coerceIn(0f, 1f)
                if (w > 1f) {
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(Color(0xFF16C78A), Color(0xFF3EF5B4)), startX = x0, endX = x1),
                        Offset(x0, cy - h / 2), Size(w, h), CornerRadius(h / 2)
                    )
                }
                drawCircle(bg, h * 0.95f, Offset(x0 + w, cy))
                drawCircle(Color(0xFF2EE6A8), h * 0.72f, Offset(x0 + w, cy))
            }
        }
    }
}

@Composable
fun AgeScreen(onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Quick check", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text("FutureQuestr is for people aged 16 and over. What year were you born?", textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it.filter { c -> c.isDigit() }.take(4) },
            label = { Text("Birth year") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        if (err.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(err, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        FQButton(onClick = {
            val y = text.toIntOrNull()
            val cy = LocalDate.now().year
            when {
                y == null || y < 1900 || y > cy -> err = "Please enter a valid year."
                cy - y < 16 -> { Store.underage = true; onDone() }
                else -> { Store.ageOk = true; onDone() }
            }
        }) { Text("Continue") }
        Spacer(Modifier.height(12.dp))
        Text("We only remember whether you passed, not your birth year.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun BlockedScreen() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("You're too young for FutureQuestr", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("This app is for people aged 16 and over. Come back when you're older, and thanks for stopping by.", textAlign = TextAlign.Center)
    }
}

@Composable
fun SignInScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(0) } // 0 idle, 1 starting, 2 waiting, 3 error
    var code by remember { mutableStateOf<Net.DeviceCode?>(null) }
    var error by remember { mutableStateOf("") }

    fun start() {
        phase = 1; error = ""
        scope.launch {
            try {
                val d = Net.startDevice()
                code = d; phase = 2
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("code", d.userCode))
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.uri))) } catch (e: Exception) { }
                val deadline = SystemClock.elapsedRealtime() + d.expiresIn * 1000L
                var interval = d.interval
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(interval * 1000L)
                    // A failed check (for example while Battery Saver blocks the app in the background)
                    // just means "not yet". Keep waiting and try again.
                    val r = try { Net.pollToken(d) } catch (e: Exception) { Net.TokenResult("pending") }
                    when (r.status) {
                        "ok" -> {
                            val tok = r.token ?: ""
                            var login = ""
                            var tries = 0
                            while (login.isEmpty() && tries < 20) {
                                try { login = Net.githubLogin(tok) } catch (e: Exception) { tries++; delay(3000L) }
                            }
                            if (login.isEmpty()) login = "player"
                            Store.token = tok; Store.login = login
                            onDone()
                            return@launch
                        }
                        "slow_down" -> interval += 5
                        "pending" -> { }
                        else -> { error = r.error; phase = 3; return@launch }
                    }
                }
                error = "That code expired. Please try again."; phase = 3
            } catch (e: Exception) {
                error = e.message ?: "Couldn't reach GitHub. Check your connection."; phase = 3
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Sign in", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("Your FutureQuestr account is your GitHub account. It keeps your Quests safe and in sync across your devices.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        when (phase) {
            0, 3 -> {
                FQButton(onClick = { start() }) { Text("Sign in with GitHub") }
                if (phase == 3) { Spacer(Modifier.height(12.dp)); Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
            1 -> CircularProgressIndicator()
            2 -> {
                val d = code
                if (d != null) {
                    Text("Enter this code on GitHub:", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(d.userCode, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = accentColor())
                    Spacer(Modifier.height(6.dp))
                    Text("The code is copied. GitHub opens in your browser. Paste it, approve, then come back here.",
                        fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    FQOutlinedButton(onClick = { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.uri))) } catch (e: Exception) { } }) { Text("Open GitHub again") }
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("Waiting for approval...", fontSize = 12.sp)
                }
            }
        }
    }
}
