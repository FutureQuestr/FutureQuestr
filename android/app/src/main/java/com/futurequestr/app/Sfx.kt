package com.futurequestr.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator

object Sfx {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val ready: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet<Int>())
    private var app: Context? = null

    fun init(c: Context) {
        if (pool != null) return
        app = c.applicationContext
        val p = SoundPool.Builder().setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = p
        p.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) ready.add(sampleId) }
        val a = c.applicationContext
        ids["tap"] = p.load(a, R.raw.tap, 1)
        ids["ding1"] = p.load(a, R.raw.ding1, 1)
        ids["ding2"] = p.load(a, R.raw.ding2, 1)
        ids["ding3"] = p.load(a, R.raw.ding3, 1)
        ids["tick"] = p.load(a, R.raw.tick, 1)
        ids["streak"] = p.load(a, R.raw.streak, 1)
        ids["levelup"] = p.load(a, R.raw.levelup, 1)
        ids["badge"] = p.load(a, R.raw.badge, 1)
        ids["challenge"] = p.load(a, R.raw.challenge, 1)
        ids["focus_start"] = p.load(a, R.raw.focus_start, 1)
        ids["focus_end"] = p.load(a, R.raw.focus_end, 1)
        ids["soft"] = p.load(a, R.raw.soft, 1)
    }

    private fun ringer(): Int {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return AudioManager.RINGER_MODE_SILENT
        return am.ringerMode
    }

    fun play(name: String, rate: Float = 1f) {
        if (pool == null || Store.muted) return   // volume follows the phone's media volume, not the ringer switch
        val id = ids[name] ?: return
        if (id !in ready) return
        pool?.play(id, 1f, 1f, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun ding() = play(listOf("ding1", "ding2", "ding3").random())

    fun buzz(ms: Long = 25) {
        if (pool == null || Store.muted || ringer() == AudioManager.RINGER_MODE_SILENT) return
        try {
            val v = app?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
    }
}
