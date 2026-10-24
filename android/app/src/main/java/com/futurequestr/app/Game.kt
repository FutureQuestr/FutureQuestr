package com.futurequestr.app

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

object Game {
    const val DAILY_CAP = 800
    val categories = listOf("study", "health", "communication", "creative", "rest")

    fun icon(cat: String): Ico = when (cat) {
        "study" -> Ico.Study
        "health" -> Ico.Health
        "communication" -> Ico.Comms
        "creative" -> Ico.Creative
        else -> Ico.Rest
    }

    fun label(cat: String): String = when (cat) {
        "communication" -> "Social"
        else -> cat.replaceFirstChar { it.uppercase() }
    }

    fun standardXp(durationMin: Int) = durationMin.coerceIn(10, 120)

    fun target(level: Int, factor: Double = 1.0): Int =
        (100.0 * level.toDouble().pow(1.3) * factor).roundToInt().coerceAtLeast(60)

    data class Challenge(val id: String, val text: String, val cat: String, val xp: Int)
    data class Badge(val id: String, val icon: Ico, val name: String, val desc: String, val test: (GameState) -> Boolean)

    private val edu = listOf(
        Challenge("e1", "Review yesterday's notes for 10 minutes", "study", 40),
        Challenge("e2", "Explain one thing you learned today out loud, as if teaching a friend", "study", 40),
        Challenge("e3", "Plan tomorrow's Quests before bed", "study", 30),
        Challenge("e4", "Read 10 pages of something you're studying", "study", 40),
        Challenge("e5", "Do one practice question you've been avoiding", "study", 45),
        Challenge("e6", "Write down 3 key points from your last lesson", "study", 35),
        Challenge("e7", "Tidy your study space for 5 minutes", "study", 20),
        Challenge("e8", "Make flashcards for one topic", "study", 45),
        Challenge("e9", "Do a 15 minute phone free study sprint", "study", 50),
        Challenge("e10", "Write one question to ask your teacher", "study", 25),
        Challenge("e11", "Summarise a chapter in five lines", "study", 45),
        Challenge("e12", "Teach someone one new fact", "study", 35)
    )
    private val health = listOf(
        Challenge("h1", "Drink a glass of water right now", "health", 20),
        Challenge("h2", "Stretch for 5 minutes", "health", 25),
        Challenge("h3", "Go for a 10 minute walk", "health", 35),
        Challenge("h4", "Get to bed on time tonight", "health", 40),
        Challenge("h5", "Take 5 slow, deep breaths", "health", 20),
        Challenge("h6", "Eat something with fruit or veg", "health", 25)
    )
    private val comm = listOf(
        Challenge("c1", "Message a friend to say thanks", "communication", 30),
        Challenge("c2", "Ask a classmate how their day was", "communication", 30),
        Challenge("c3", "Ask one question in class or in a study group", "communication", 40),
        Challenge("c4", "Tell someone about one of your goals", "communication", 35),
        Challenge("c5", "Call or message family", "communication", 30),
        Challenge("c6", "Give someone a genuine compliment", "communication", 25)
    )
    val allChallenges = edu + health + comm

    fun todaysChallenges(day: String): List<Challenge> {
        val r = Random(day.hashCode().toLong())
        return edu.shuffled(r).take(2) + (health + comm).shuffled(r).take(1)
    }

    val badges = listOf(
        Badge("first", Ico.Quests, "First Quest", "Complete your first Quest") { it.totalDone >= 1 },
        Badge("q10", Ico.Quests, "Getting Going", "Complete 10 Quests") { it.totalDone >= 10 },
        Badge("q50", Ico.Quests, "Quest Regular", "Complete 50 Quests") { it.totalDone >= 50 },
        Badge("q100", Ico.Star, "Century", "Complete 100 Quests") { it.totalDone >= 100 },
        Badge("s3", Ico.Flame, "On a Roll", "3 day streak") { it.bestStreak >= 3 },
        Badge("s7", Ico.Flame, "Full Week", "7 day streak") { it.bestStreak >= 7 },
        Badge("s14", Ico.Flame, "Fortnight Focus", "14 day streak") { it.bestStreak >= 14 },
        Badge("s30", Ico.Flame, "Habit Master", "30 day streak") { it.bestStreak >= 30 },
        Badge("l5", Ico.Level, "Level 5", "Reach level 5") { it.level >= 5 },
        Badge("l10", Ico.Level, "Level 10", "Reach level 10") { it.level >= 10 },
        Badge("l20", Ico.Level, "Level 20", "Reach level 20") { it.level >= 20 },
        Badge("f1", Ico.Focus, "Locked In", "Finish a Focus session") { it.focusSessions >= 1 },
        Badge("f10", Ico.Focus, "Deep Focus", "Finish 10 Focus sessions") { it.focusSessions >= 10 },
        Badge("h10", Ico.Clock, "10 Hours", "10 hours of Quests and Focus") { it.totalMinutes >= 600 },
        Badge("h50", Ico.Clock, "50 Hours", "50 hours of Quests and Focus") { it.totalMinutes >= 3000 },
        Badge("c5", Ico.Check, "Challenger", "Complete 5 daily challenges") { it.challengeTotal >= 5 },
        Badge("c25", Ico.Check, "Challenge Champ", "Complete 25 daily challenges") { it.challengeTotal >= 25 }
    )
}
