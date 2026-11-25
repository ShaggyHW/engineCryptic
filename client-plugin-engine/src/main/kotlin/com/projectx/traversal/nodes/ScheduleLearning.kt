package com.projectx.traversal.nodes

import com.projectx.traversal.hops.HopLearning
import com.projectx.traversal.hops.ScheduleStore
import world.gregs.voidps.type.Tile
import kotlin.math.sqrt

/**
 * What the hop schedule learns from its own outcomes instead of fixed numbers, kept in [ScheduleStore]:
 * - hold margins (`walk`, `beyond`): the gain a hold was kept for less the gain it went for, as round(mean + sd);
 * - the ordinary hop per ability (`surge`, `dive`): the mean gain real casts were made for;
 * - combo trust per spot (`combo-at:`) and the combo hold margin (`combo`);
 * - how long a landing holds the player (`lock:`), and interaction times (`dead:`, measured by [Interactions]).
 *
 * The old constants are the priors, worth [PRIOR_WEIGHT] samples ([TIMING_PRIOR_WEIGHT] for a timing).
 */
internal object ScheduleLearning {
    const val PRIOR_WEIGHT = 5.0
    const val TIMING_PRIOR_WEIGHT = 1.0
    const val MAX_MARGIN = 12.0

    /** A combo spot whose one-tick combos landed right less often than this is split up... */
    const val COMBO_TRUST_RATE = 0.5

    /** ...once it has at least this many outcomes. */
    const val COMBO_TRUST_MIN = 2

    /** A one-tick combo landing within this many tiles of its predicted end counts as landed right. */
    const val COMBO_OK_TILES = 1

    @Volatile
    private var announced = false

    /** The margins and ordinary-hop value the schedule decides with now. */
    fun params(surge: Boolean): HopSchedule.Params {
        announce()
        return HopSchedule.Params(
            baseGain(surge),
            margin("walk", HopSchedule.HOLD_MARGIN.toDouble()),
            margin("beyond", HopSchedule.HOLD_MARGIN_BEYOND.toDouble()),
        )
    }

    fun baseGain(surge: Boolean): Int = Math.round(mean(gainKey(surge), HopSchedule.BASE_GAIN.toDouble(), PRIOR_WEIGHT)).toInt()

    /** round(mean + sd) of [key]'s samples with the prior, 1 to [MAX_MARGIN]. */
    fun margin(key: String, prior: Double): Int {
        val s = ScheduleStore.get(key)
        val w = s.w + PRIOR_WEIGHT
        val m = (s.s1 + PRIOR_WEIGHT * prior) / w
        val variance = maxOf(0.0, (s.s2 + PRIOR_WEIGHT * prior * prior) / w - m * m)
        return Math.round((m + sqrt(variance)).coerceIn(1.0, MAX_MARGIN)).toInt()
    }

    fun margin(key: String, prior: Int): Int = margin(key, prior.toDouble())

    fun mean(key: String, prior: Double, priorWeight: Double): Double {
        val s = ScheduleStore.get(key)
        return (s.s1 + priorWeight * prior) / (s.w + priorWeight)
    }

    /** How many samples [key] has, the prior not counted. */
    fun samples(key: String): Long = Math.round(ScheduleStore.get(key).w)

    /** A hold kept for [keptGain] went for [realGain]; [beyond] when kept past an interaction. */
    fun holdDone(beyond: Boolean, keptGain: Int, realGain: Int, owner: String) {
        val key = if (beyond) "beyond" else "walk"
        val prior = if (beyond) HopSchedule.HOLD_MARGIN_BEYOND else HopSchedule.HOLD_MARGIN
        val was = margin(key, prior)
        ScheduleStore.add(key, keptGain - realGain)
        println(
            "[$owner] hold " + (if (beyond) "past an interaction" else "on the walk") + " kept for +$keptGain went for +$realGain: " +
                "margin $was -> ${margin(key, prior)} (${samples(key)} holds${notLearned()})",
        )
    }

    /** A hold teaches the margin only when it was kept for a real spot: a probe spot's gain is what a crossing would give. */
    fun learnsMargin(keptGain: Int, keptProbe: Boolean): Boolean = keptGain > 0 && !keptProbe

    /** The key of a combo planned as [first] then the other, via [mid] to [end]; null when any is unknown. */
    fun comboKey(first: String?, mid: Tile?, end: Tile?): String? {
        if (first == null || mid == null || end == null) return null
        return "combo-at:$first:${mid.x},${mid.y},${mid.plane}>${end.x},${end.y}"
    }

    fun comboOutcome(key: String?, ok: Boolean) {
        if (key != null) ScheduleStore.add(key, if (ok) 1 else 0)
    }

    /**
     * False when one-tick combos at [key] have mostly missed: its two hops then go a tick apart, where each is seen
     * landing and learned on its own.
     */
    fun comboTrusted(key: String?): Boolean {
        if (key == null) return true
        val s = ScheduleStore.get(key)
        return s.w < COMBO_TRUST_MIN || s.s1 / s.w >= COMBO_TRUST_RATE
    }

    /** "a of n" landed right, for the logs; empty with no outcomes. */
    fun comboRecord(key: String?): String {
        if (key == null) return ""
        val s = ScheduleStore.get(key)
        return if (s.w <= 0) "" else "${Math.round(s.s1)} of ${Math.round(s.w)}"
    }

    /** The margin for keeping both abilities for a combo ahead, starting from the walk margin single holds have taught. */
    fun comboMargin(): Int = margin("combo", margin("walk", HopSchedule.HOLD_MARGIN))

    /** Both kept for a combo planned at [keptGain] got [realGain] (0: the hold was given up). */
    fun comboHoldDone(keptGain: Int, realGain: Int, owner: String) {
        val prior = margin("walk", HopSchedule.HOLD_MARGIN)
        val was = margin("combo", prior)
        ScheduleStore.add("combo", keptGain - realGain)
        println(
            "[$owner] combo hold kept for +$keptGain went for +$realGain: combo margin $was -> ${margin("combo", prior)} " +
                "(${samples("combo")} holds${notLearned()})",
        )
    }

    /** A hop of this ability was cast for [gain]: its own, not the dive it sets up. */
    fun cast(surge: Boolean, gain: Int) {
        if (gain > 0) ScheduleStore.add(gainKey(surge), gain)
    }

    /** The hop-free time of [key], else of its [type], with [default] as the prior. */
    fun deadMs(key: String, type: String, default: Long): Long = Interactions.deadMs(key, type, default)

    /** How long a landing sent by [key] (`NpcNode:Charter`, `LodestoneNode`, `teleport`) holds the player, learned. */
    fun lockMs(key: String, default: Long): Long = Math.round(mean("lock:$key", default.toDouble(), TIMING_PRIOR_WEIGHT))

    fun lock(key: String, ms: Long) = ScheduleStore.add("lock:$key", ms.toDouble())

    fun timingCount(): Int = ScheduleStore.keys("dead:").size

    private fun gainKey(surge: Boolean) = if (surge) "surge" else "dive"

    private fun notLearned() = if (HopLearning.enabled()) "" else ", not learned: this client does not learn hops"

    private fun announce() {
        if (announced) return
        announced = true
        println(
            "[hops] schedule learned so far (${ScheduleStore.describe()}): walk margin ${margin("walk", HopSchedule.HOLD_MARGIN)} " +
                "(${samples("walk")} holds), past-interaction margin ${margin("beyond", HopSchedule.HOLD_MARGIN_BEYOND)} " +
                "(${samples("beyond")}), ordinary surge +${baseGain(true)} (${samples("surge")}), dive +${baseGain(false)} " +
                "(${samples("dive")}), ${timingCount()} interaction timings",
        )
    }
}
