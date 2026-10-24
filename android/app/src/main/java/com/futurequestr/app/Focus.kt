package com.futurequestr.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Focus session state lives in plain SharedPreferences so the accessibility service can read it without the UI. */
object Focus {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("fq", 0)

    fun endTime(c: Context): Long = sp(c).getLong("focusEnd", 0L)
    fun active(c: Context): Boolean = endTime(c) > System.currentTimeMillis()

    fun start(c: Context, minutes: Int) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        sp(c).edit().putLong("focusEnd", end).putInt("focusMinutes", minutes).putInt("focusPending", 0).apply()
        dnd(c, true)
        WorkManager.getInstance(c).enqueueUniqueWork(
            "focus_end", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FocusEndWorker>().setInitialDelay(minutes.toLong(), TimeUnit.MINUTES).build()
        )
    }

    /** Ends the session early with no reward. */
    fun cancel(c: Context) {
        sp(c).edit().putLong("focusEnd", 0L).putInt("focusPending", 0).apply()
        dnd(c, false)
        WorkManager.getInstance(c).cancelUniqueWork("focus_end")
    }

    /** If the timer has run out, marks the session as finished and waiting for its reward. */
    fun tick(c: Context): Boolean {
        val s = sp(c)
        val end = s.getLong("focusEnd", 0L)
        if (end != 0L && System.currentTimeMillis() >= end) {
            s.edit().putLong("focusEnd", 0L).putInt("focusPending", s.getInt("focusMinutes", 0)).apply()
            dnd(c, false)
            return true
        }
        return false
    }

    fun consumePending(c: Context): Int {
        val s = sp(c)
        val m = s.getInt("focusPending", 0)
        if (m > 0) s.edit().putInt("focusPending", 0).apply()
        return m
    }

    private fun dnd(c: Context, on: Boolean) {
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) return
            val s = sp(c)
            if (on) {
                s.edit().putInt("prevFilter", nm.currentInterruptionFilter).putBoolean("dndApplied", true).apply()
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            } else if (s.getBoolean("dndApplied", false)) {
                val prev = s.getInt("prevFilter", NotificationManager.INTERRUPTION_FILTER_ALL)
                nm.setInterruptionFilter(if (prev == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) NotificationManager.INTERRUPTION_FILTER_ALL else prev)
                s.edit().putBoolean("dndApplied", false).apply()
            }
        } catch (e: Exception) { }
    }
}

/**
 * While a Focus session runs, covers any app that is not on the allowed list with an
 * "App locked by FutureQuestr" screen. Uses the accessibility overlay window type, so no
 * "display over other apps" permission is needed. It only reads which app is in front.
 */
class FocusService : AccessibilityService() {
    private var overlay: View? = null
    private var allowed: Set<String> = emptySet()
    private var allowedAt = 0L

    override fun onServiceConnected() { super.onServiceConnected() }
    override fun onInterrupt() { hide() }
    override fun onDestroy() { hide(); super.onDestroy() }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (!Focus.active(this)) { hide(); return }
        if (pkg == packageName) return   // our own overlay or app
        if (isAllowed(pkg)) hide() else show()
    }

    private fun isAllowed(pkg: String): Boolean {
        if (System.currentTimeMillis() - allowedAt > 60_000L || allowed.isEmpty()) {
            allowed = buildAllowed(); allowedAt = System.currentTimeMillis()
        }
        val p = pkg.lowercase()
        return pkg in allowed || p.contains("inputmethod") || p.contains("keyboard") || p.startsWith("com.android.systemui")
    }

    private fun buildAllowed(): Set<String> {
        val s = HashSet<String>()
        s.addAll(listOf("com.android.systemui", "android", "com.android.phone", "com.android.server.telecom",
            "com.android.incallui", "com.google.android.dialer", "com.android.emergency"))
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, 0).forEach { s.add(it.activityInfo.packageName) }
        } catch (e: Exception) { }
        try { (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { s.add(it) } } catch (e: Exception) { }
        try { Telephony.Sms.getDefaultSmsPackage(this)?.let { s.add(it) } } catch (e: Exception) { }
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.forEach { s.add(it.packageName) }
        } catch (e: Exception) { }
        return s
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun show() {
        if (overlay != null) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val root = FrameLayout(this)
            root.setBackgroundColor(Color.parseColor("#F20B0E2C"))
            root.isClickable = true

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            col.setPadding(dp(32), dp(32), dp(32), dp(32))

            val icon = LockView(this)
            val title = TextView(this)
            title.text = "App locked by FutureQuestr"
            title.setTextColor(Color.WHITE); title.textSize = 24f; title.setTypeface(null, Typeface.BOLD); title.gravity = Gravity.CENTER
            val sub = TextView(this)
            sub.text = "Focus Mode is on. Stay with your Quests."
            sub.setTextColor(Color.parseColor("#C5CAF0")); sub.textSize = 16f; sub.gravity = Gravity.CENTER
            sub.setPadding(0, dp(12), 0, dp(40))

            val exit = TextView(this)
            exit.text = "Exit"
            exit.setTextColor(Color.parseColor("#05241A")); exit.textSize = 20f; exit.setTypeface(null, Typeface.BOLD)
            exit.gravity = Gravity.CENTER
            val circle = GradientDrawable(); circle.shape = GradientDrawable.OVAL; circle.setColor(Color.parseColor("#2EE6A8"))
            exit.background = circle
            exit.setOnClickListener { performGlobalAction(GLOBAL_ACTION_HOME); hide() }

            col.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(20) }); col.addView(title); col.addView(sub)
            col.addView(exit, LinearLayout.LayoutParams(dp(110), dp(110)))
            root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(root, lp)
            overlay = root
        } catch (e: Exception) { overlay = null }
    }

    private fun hide() {
        val v = overlay ?: return
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) { }
        overlay = null
    }
}

/** Simple outlined padlock, drawn so the lock screen needs no emoji or image. */
private class LockView(c: Context) : View(c) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2EE6A8"); style = Paint.Style.STROKE
        strokeWidth = 3.2f * c.resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2EE6A8") }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRoundRect(RectF(w * 0.18f, h * 0.46f, w * 0.82f, h * 0.92f), w * 0.1f, w * 0.1f, line)
        canvas.drawArc(RectF(w * 0.3f, h * 0.08f, w * 0.7f, h * 0.52f), 180f, 180f, false, line)
        canvas.drawLine(w * 0.3f, h * 0.30f, w * 0.3f, h * 0.46f, line)
        canvas.drawLine(w * 0.7f, h * 0.30f, w * 0.7f, h * 0.46f, line)
        canvas.drawCircle(w * 0.5f, h * 0.67f, w * 0.06f, dot)
    }
}
