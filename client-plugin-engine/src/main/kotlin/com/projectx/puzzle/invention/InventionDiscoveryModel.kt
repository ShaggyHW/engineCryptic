package com.projectx.puzzle.invention

import kotlin.math.abs
import kotlin.math.sqrt

const val TRACK_SLOTS = 5

enum class SlotHint { NEUTRAL, LIKELY, LOCKED, SWAP_FIRST, SWAP_SECOND }

class Advice(
    val solved: Boolean,
    val swapFirst: Int,
    val swapSecond: Int,
    val slotHints: Array<SlotHint>,
    val believedBest: IntArray,
)

/**
 * Learns a discovery blueprint from scored arrangements. The penalty is the sum of a hidden cost per (slot, module)
 * pair, so every score is one linear equation; a ridge-regularised least-squares fit of the 25 costs predicts every
 * arrangement and picks the next one worth scoring.
 */
class InventionDiscoveryModel {

    private val scores = LinkedHashMap<Int, Scored>()
    private var best: IntArray? = null

    val observationCount: Int get() = scores.size

    var bestPenalty: Int = UNSCORED
        private set

    val bestArrangement: IntArray? get() = best?.copyOf()

    val solved: Boolean get() = bestPenalty == 0

    fun reset() {
        scores.clear()
        best = null
        bestPenalty = UNSCORED
    }

    fun record(arrangement: IntArray, penalty: Int) {
        if (!isArrangement(arrangement) || penalty < 0) return
        val kept = arrangement.copyOf()
        scores[codeOf(kept)] = Scored(kept, penalty)
        if (penalty < bestPenalty) best = kept
        bestPenalty = minOf(bestPenalty, penalty)
    }

    fun penaltyOf(arrangement: IntArray): Int? =
        if (isArrangement(arrangement)) scores[codeOf(arrangement)]?.penalty else null

    fun swapToward(current: IntArray, target: IntArray): Pair<Int, Int> {
        if (!isArrangement(current) || !isArrangement(target)) return NO_SWAP
        val first = (0 until TRACK_SLOTS).firstOrNull { current[it] != target[it] } ?: return NO_SWAP
        return first to current.indexOf(target[first])
    }

    fun advise(current: IntArray): Advice {
        if (!isArrangement(current)) return restingAdvice(current, solved = false)
        if (penaltyOf(current) == 0) return restingAdvice(current, solved = true)

        val costs = fitCosts()
        val predictions = DoubleArray(ALL.size) { predicted(costs, ALL[it]) }
        val believed = cheapestPrediction(current, predictions) { true }
            ?: error("an arrangement always exists")
        val target = if (penaltyOf(ALL[believed]) == null) believed
        else cheapestPrediction(current, predictions) { penaltyOf(ALL[it]) == null } ?: believed
        val believedBest = ALL[believed].copyOf()

        val marks = Array(TRACK_SLOTS) { slot ->
            when {
                current[slot] != believedBest[slot] -> SlotHint.NEUTRAL
                confident(costs, slot, current[slot]) -> SlotHint.LOCKED
                else -> SlotHint.LIKELY
            }
        }
        val (first, second) = swapToward(current, ALL[target])
        if (first >= 0) {
            marks[first] = SlotHint.SWAP_FIRST
            marks[second] = SlotHint.SWAP_SECOND
        }
        return Advice(solved = false, swapFirst = first, swapSecond = second, slotHints = marks, believedBest = believedBest)
    }

    private fun restingAdvice(current: IntArray, solved: Boolean) =
        Advice(solved, -1, -1, Array(TRACK_SLOTS) { SlotHint.NEUTRAL }, current.copyOf())

    private fun cheapestPrediction(current: IntArray, predictions: DoubleArray, eligible: (Int) -> Boolean): Int? {
        var chosen = -1
        for (candidate in ALL.indices) {
            if (!eligible(candidate)) continue
            if (chosen < 0) {
                chosen = candidate
                continue
            }
            val gap = predictions[candidate] - predictions[chosen]
            val closer = differingSlots(ALL[candidate], current) < differingSlots(ALL[chosen], current)
            if (gap < -TIE || (abs(gap) <= TIE && closer)) chosen = candidate
        }
        return chosen.takeIf { it >= 0 }
    }

    private fun confident(costs: DoubleArray, slot: Int, module: Int): Boolean {
        if (observationCount < CONFIDENT_AFTER) return false
        val own = costs[pair(slot, module)]
        val rival = (1..TRACK_SLOTS).filter { it != module }.minOf { costs[pair(slot, it)] }
        val spread = maxOf(costs.max() - costs.min(), 1e-9)
        return (rival - own) / spread > CONFIDENT_MARGIN
    }

    /** Least squares on the normal equations by Cholesky; the ridge keeps them positive definite while scores are few. */
    private fun fitCosts(): DoubleArray {
        val normal = Array(PAIRS) { DoubleArray(PAIRS) }
        val rhs = DoubleArray(PAIRS)
        for (scored in scores.values) {
            val used = IntArray(TRACK_SLOTS) { pair(it, scored.arrangement[it]) }
            for (row in used) {
                rhs[row] += scored.penalty.toDouble()
                for (column in used) normal[row][column] += 1.0
            }
        }
        for (i in 0 until PAIRS) normal[i][i] += RIDGE_WEIGHT
        val lower = Array(PAIRS) { DoubleArray(PAIRS) }
        for (i in 0 until PAIRS) {
            for (j in 0..i) {
                var sum = normal[i][j]
                for (k in 0 until j) sum -= lower[i][k] * lower[j][k]
                lower[i][j] = if (i == j) sqrt(sum) else sum / lower[j][j]
            }
        }
        val forward = DoubleArray(PAIRS)
        for (i in 0 until PAIRS) {
            var sum = rhs[i]
            for (k in 0 until i) sum -= lower[i][k] * forward[k]
            forward[i] = sum / lower[i][i]
        }
        val costs = DoubleArray(PAIRS)
        for (i in PAIRS - 1 downTo 0) {
            var sum = forward[i]
            for (k in i + 1 until PAIRS) sum -= lower[k][i] * costs[k]
            costs[i] = sum / lower[i][i]
        }
        return costs
    }

    private fun predicted(costs: DoubleArray, arrangement: IntArray): Double {
        var total = 0.0
        for (slot in 0 until TRACK_SLOTS) total += costs[pair(slot, arrangement[slot])]
        return total
    }

    private class Scored(val arrangement: IntArray, val penalty: Int)

    companion object {
        private const val PAIRS = TRACK_SLOTS * TRACK_SLOTS
        private const val RIDGE_WEIGHT = 1e-3
        private const val UNSCORED = Int.MAX_VALUE
        private const val TIE = 1e-9
        private const val CONFIDENT_AFTER = 5
        private const val CONFIDENT_MARGIN = 0.12
        private val NO_SWAP = -1 to -1

        val ALL_ARRANGEMENTS: List<IntArray> = orderings((1..TRACK_SLOTS).toList())

        private val ALL: List<IntArray> get() = ALL_ARRANGEMENTS

        fun isArrangement(a: IntArray): Boolean {
            if (a.size != TRACK_SLOTS) return false
            val present = BooleanArray(TRACK_SLOTS + 1)
            for (module in a) {
                if (module !in 1..TRACK_SLOTS || present[module]) return false
                present[module] = true
            }
            return true
        }

        private fun pair(slot: Int, module: Int): Int = slot * TRACK_SLOTS + module - 1

        private fun codeOf(arrangement: IntArray): Int = arrangement.fold(0) { code, module -> code * 8 + module }

        private fun differingSlots(a: IntArray, b: IntArray): Int = (0 until TRACK_SLOTS).count { a[it] != b[it] }

        private fun orderings(remaining: List<Int>): List<IntArray> {
            if (remaining.isEmpty()) return listOf(IntArray(0))
            return remaining.flatMap { head -> orderings(remaining - head).map { tail -> intArrayOf(head, *tail) } }
        }
    }
}
