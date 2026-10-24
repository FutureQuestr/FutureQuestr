package com.futurequestr.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.app.NotificationManager
import androidx.core.content.ContextCompat

data class BrandHelp(val battery: String, val restricted: String, val accessibility: String)

object Perms {
    val brands = listOf("Samsung", "Xiaomi (Redmi, POCO)", "Google Pixel", "OPPO (OnePlus, realme)", "Vivo", "Other")

    fun detectBrand(): String {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        return when {
            m.contains("samsung") -> brands[0]
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> brands[1]
            m.contains("google") -> brands[2]
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> brands[3]
            m.contains("vivo") || m.contains("iqoo") -> brands[4]
            else -> brands[0]
        }
    }

    fun help(brand: String): BrandHelp = when (brand) {
        brands[0] -> BrandHelp(
            "Settings > Battery > Background usage limits > Never sleeping apps > add FutureQuestr.",
            "Settings > Apps > FutureQuestr > three dots (top right) > Allow restricted settings.",
            "Settings > Accessibility > Installed apps > FutureQuestr > switch on.")
        brands[1] -> BrandHelp(
            "Settings > Apps > Manage apps > FutureQuestr > Battery saver > No restrictions, and switch Autostart on.",
            "Settings > Apps > Manage apps > FutureQuestr > three dots > Allow restricted settings.",
            "Settings > Additional settings > Accessibility > Downloaded apps > FutureQuestr > switch on.")
        brands[2] -> BrandHelp(
            "Usually nothing extra is needed. Tap Allow if asked.",
            "Settings > Apps > FutureQuestr > three dots (top right) > Allow restricted settings.",
            "Settings > Accessibility > Downloaded apps > FutureQuestr > switch on.")
        brands[3] -> BrandHelp(
            "Settings > Battery > App battery management > FutureQuestr > Allow background activity. Also switch on Auto launch in the app's info page.",
            "Settings > Apps > App management > FutureQuestr > three dots > Allow restricted settings.",
            "Settings > Additional settings > Accessibility > Downloaded apps > FutureQuestr > switch on.")
        brands[4] -> BrandHelp(
            "Settings > Battery > Background power consumption > FutureQuestr > Allow. Also allow Autostart in the app's info page.",
            "Settings > Apps > FutureQuestr > three dots > Allow restricted settings.",
            "Settings > Accessibility > Downloaded services > FutureQuestr > switch on.")
        else -> BrandHelp(
            "Settings > Battery > find FutureQuestr > set to Unrestricted or No restrictions.",
            "Settings > Apps > FutureQuestr > three dots > Allow restricted settings.",
            "Settings > Accessibility > Downloaded apps > FutureQuestr > switch on.")
    }

    fun notifOk(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun batteryOk(c: Context): Boolean =
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

    fun a11yOk(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(":").any { it.startsWith(c.packageName + "/") }
    }

    fun dndOk(c: Context) = (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

    fun installOk(c: Context) = c.packageManager.canRequestPackageInstalls()

    fun openBattery(c: Context) {
        try {
            c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName)))
        } catch (e: Exception) {
            c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openAppInfo(c: Context) =
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)))

    fun openAccessibility(c: Context) = c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    fun openDnd(c: Context) = c.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    fun openNotifSettings(c: Context) =
        c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName))
}
