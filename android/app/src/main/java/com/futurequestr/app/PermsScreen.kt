package com.futurequestr.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class PItem(
    val title: String, val why: String, val hint: String, val ok: Boolean,
    val label: String, val action: () -> Unit,
    val extraLabel: String? = null, val extra: (() -> Unit)? = null
)

@Composable
fun PermsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val tick = UiBus.resume.value   // recheck whenever the app returns to the front
    var brand by remember { mutableStateOf(Store.brand) }
    var menu by remember { mutableStateOf(false) }
    var notifTries by remember { mutableStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        UiBus.resume.value = UiBus.resume.value + 1
    }
    val help = Perms.help(brand)

    val items = ArrayList<PItem>()
    items.add(PItem(
        "Notifications", "So FutureQuestr can remind you about Quests and send friendly nudges.",
        "Tap Allow on the popup.", Perms.notifOk(ctx), "Allow",
        {
            notifTries++
            if (Build.VERSION.SDK_INT >= 33 && notifTries <= 1) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else Perms.openNotifSettings(ctx)
        }
    ))
    items.add(PItem(
        "Run in the background", "Stops your phone from silently putting reminders and Focus Mode to sleep.",
        help.battery, Perms.batteryOk(ctx), "Allow", { Perms.openBattery(ctx) }
    ))
    if (Build.VERSION.SDK_INT >= 33) {
        items.add(PItem(
            "Allow restricted settings", "Android hides the Accessibility switch for apps installed outside the Play Store until you allow this.",
            help.restricted, Store.restrictedAck || Perms.a11yOk(ctx), "Open app info", { Perms.openAppInfo(ctx) },
            "I've done this", { Store.restrictedAck = true; UiBus.resume.value = UiBus.resume.value + 1 }
        ))
    }
    items.add(PItem(
        "Focus Mode lock screen", "Lets FutureQuestr see which app is open so it can show the 'App locked' screen during Focus sessions. It does not read your screen, messages or passwords.",
        help.accessibility, Perms.a11yOk(ctx), "Open settings", { Perms.openAccessibility(ctx) }
    ))
    items.add(PItem(
        "Do Not Disturb access", "Silences other notifications while Focus Mode is running.",
        "Find FutureQuestr in the list and switch it on.", Perms.dndOk(ctx), "Open settings", { Perms.openDnd(ctx) }
    ))
    items.add(PItem(
        "Install updates", "Lets FutureQuestr install new versions when you tap Update.",
        "Switch on 'Allow from this source'.", Perms.installOk(ctx), "Open settings", { Updater.openInstallPermission(ctx) }
    ))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Set up FutureQuestr", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("A few switches make reminders and Focus Mode work properly. Each one takes a few seconds, and you can skip any of them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Your phone brand", fontWeight = FontWeight.SemiBold)
        Box {
            FQOutlinedButton(onClick = { menu = true }) { Text(brand); Spacer(Modifier.width(6.dp)); FQIcon(Ico.ChevronDown, LocalContentColor.current, 16.dp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Perms.brands.forEach { b ->
                    DropdownMenuItem(text = { Text(b) }, onClick = { brand = b; Store.brand = b; menu = false })
                }
            }
        }
        Text("Menu names can differ slightly between phone versions.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        items.forEachIndexed { i, it ->
            Panel(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (it.ok) FQIcon(Ico.Check, accentColor(), 20.dp) else Text("${i + 1}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(it.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(it.why, fontSize = 13.sp)
                    if (!it.ok) {
                        Spacer(Modifier.height(4.dp))
                        Text(it.hint, fontSize = 13.sp, color = accentColor())
                        Spacer(Modifier.height(8.dp))
                        Row {
                            FQButton(onClick = it.action) { Text(it.label) }
                            if (it.extra != null && it.extraLabel != null) {
                                Spacer(Modifier.width(8.dp))
                                FQOutlinedButton(onClick = it.extra) { Text(it.extraLabel) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val allOk = items.all { it.ok }
        FQButton(onClick = { Store.permsDone = true; onDone() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (allOk) "All set. Continue" else "Continue (finish the rest later)")
        }
        if (tick < 0) Text("")
    }
}
