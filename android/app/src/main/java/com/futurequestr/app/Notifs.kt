package com.futurequestr.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class FQApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("nudges", "Friendly nudges", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("focus", "Focus Mode", NotificationManager.IMPORTANCE_DEFAULT))
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "fq_periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PeriodicWorker>(30, TimeUnit.MINUTES).build()
        )
    }
}

object Notifs {
    fun post(c: Context, channel: String, id: Int, title: String, text: String) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, channel).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).build()
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (e: SecurityException) { }
    }
}

object Nudges {
    private val soon = listOf(
        "Your next Quest is about to start. Dramatic entrance optional.",
        "Quest incoming! Future you says thanks in advance.",
        "Ready, set, XP! Your Quest starts in a few minutes.",
        "Tiny reminder: a Quest is warming up its engines.",
        "Your timetable is tapping its watch. Gently.",
        "Psst... a Quest is about to begin. Snacks optional, focus recommended.",
        "Heads up! Quest time is almost here. You've got this.",
        "Almost go time. Your Quest is wearing its lucky socks."
    )
    private val missed = listOf(
        "Your timetable called. It misses you.",
        "Quick, XP is on sale today. Price: one Quest.",
        "Your Quests are lonely. Say hi?",
        "Good news: today is still unspent. Open up and spend it well.",
        "One small Quest now beats a big plan later. Just saying.",
        "Level ups don't collect themselves. Peek in?",
        "A tiny tap today keeps the study gremlins away.",
        "Your future self left you a note: 'please open FutureQuestr'."
    )
    private val streak = listOf(
        "Streak alert! Your streak is wearing a tiny 'please don't' sign.",
        "Your streak is on the edge of its seat. One Quest keeps it going.",
        "Don't let your streak go cold. A quick Quest will do it.",
        "Your streak says: 'I believe in you.' It also says: 'hurry up'.",
        "Keep the flame alive. One Quest is all it takes.",
        "Streak check: still alive, slightly nervous. Help it out?",
        "Your streak would like one (1) Quest, please.",
        "A streak this good deserves another day. Open the app?"
    )
    private val level = listOf(
        "The next level is just a couple of Quests away. Totally doable. Probably.",
        "So close to the next level you can nearly taste it. Tastes like XP.",
        "Your XP bar is almost full. Give it a nudge?",
        "One more Quest and the next level is yours.",
        "Nearly there! Your next level is stretching its arms.",
        "XP bar looks hungry. Feed it a Quest?",
        "The next level is right around the corner. Wave hello.",
        "Almost levelled up! A quick Quest could do it."
    )
    private val challenge = listOf(
        "Today's challenges are fresh out of the oven.",
        "New daily challenges! Bonus XP is waiting.",
        "Three tiny challenges, one big smile. Take a look?",
        "Your daily challenges are ready. Bonus XP included.",
        "Fresh challenges just dropped. Pick your favourite.",
        "Daily challenge time! Quick wins, real XP.",
        "Challenges are up. Grab some easy XP.",
        "A new day, new challenges, same awesome you."
    )

    fun maybe(c: Context) {
        val sp = Store.prefs
        if (!Perms.notifOk(c) || Focus.active(c)) return
        val now = LocalDateTime.now()
        val hour = now.hour
        if (hour >= 22 || hour < 8) return
        val today = LocalDate.now().toString()
        var count = sp.getInt("nudgeCount", 0)
        if (sp.getString("nudgeDay", "") != today) { count = 0 }
        if (count >= 2) return
        if (sp.getInt("ignored", 0) >= 4) return
        val lastAt = sp.getLong("lastNudgeAt", 0L)
        if (System.currentTimeMillis() - lastAt < 3 * 60 * 60 * 1000L) return

        val nowMs = System.currentTimeMillis()
        val active = Store.visibleQuests().filter { !it.done && it.day == today }
        val upcoming = active.any { val s = questStartMs(it); s > nowMs && s - nowMs <= 20 * 60 * 1000L }
        val doneToday = Store.gs.lastActiveDay == today
        val opened = sp.getString("lastOpenDay", "") == today
        val xpLeft = Store.gs.target - Store.gs.xpInLevel

        val pool: List<String>? = when {
            upcoming -> soon
            !doneToday && Store.effectiveStreak() >= 3 && hour >= 18 -> streak
            !doneToday && xpLeft in 1..80 && hour >= 16 -> level
            !opened && hour in 11..14 -> challenge
            !opened && hour >= 16 -> missed
            else -> null
        }
        if (pool == null) return
        Notifs.post(c, "nudges", 1001, "FutureQuestr", pool.random())
        sp.edit().putString("nudgeDay", today).putInt("nudgeCount", count + 1)
            .putInt("ignored", sp.getInt("ignored", 0) + 1).putLong("lastNudgeAt", System.currentTimeMillis()).apply()
    }
}

class PeriodicWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        Store.init(c)
        try { Focus.tick(c) } catch (e: Exception) { }
        try {
            val info = Updater.check()
            if (info != null) BackgroundUpdate.handle(c, info)
        } catch (e: Exception) { }
        try { Nudges.maybe(c) } catch (e: Exception) { }
        return Result.success()
    }
}

class FocusEndWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        Store.init(c)
        if (Focus.tick(c)) {
            Notifs.post(c, "focus", 1003, "Focus session complete", "Nice work! Open FutureQuestr to collect your XP.")
        }
        return Result.success()
    }
}
