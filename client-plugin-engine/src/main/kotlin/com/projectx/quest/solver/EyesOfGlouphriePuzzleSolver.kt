package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestStep

/**
 * The Eyes of Glouphrie's machine: each number shown must be matched by exactly so many discs whose values (colour
 * times shape) add up to it. Advises which carried discs to place, which to exchange, and what to fetch.
 */
internal object EyesOfGlouphriePuzzleSolver : QuestStepSolver {
    override val id get() = "eyes-of-glouphrie.oaknocks-puzzle"

    private class SlotVars(val target: String, val current: String, val discs: Int, val label: String) {
        val targetVarbit by lazy { Names.varbit(target) }
        val currentVarbit by lazy { Names.varbit(current) }
    }

    private val tutorial = SlotVars("eyeglo_coin_value_1", "eyeglo_unlock_val", 1, "Phase 1 (tutorial)")
    private val machineSlots = listOf(
        SlotVars("eyeglo_coin_value_2", "eyeglo_operate1_val", 1, "Slot 1"),
        SlotVars("eyeglo_coin_value_3", "eyeglo_operate2_val", 2, "Slot 2"),
        SlotVars("eyeglo_coin_value_4", "eyeglo_operate3_val", 3, "Slot 3"),
    )

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        val operating = machineSlots.map(::read).filter { it.target > 0 }
        val active = operating.ifEmpty { listOf(read(tutorial)).filter { it.target > 0 } }
        if (active.isEmpty()) {
            return adviceOnly(
                "Oaknock's Machine - match each number with exactly that many discs (1, then 1, 2, 3) whose values sum to it.",
                "Disc value = colour × shape. R=1 O=2 Y=3 G=4 B=5 I=6 V=7 ;  C=1 T=3 S=4 P=5.",
                "Open the machine for live hints; use Oaknock's exchanger to morph spare discs, talk to Brimstail for more.",
            )
        }
        return adviceOnly(*discAdvice(active, SolverWorld.carried()).toTypedArray())
    }

    private fun read(vars: SlotVars) = DiscSlot(
        vars.label,
        SolverWorld.varbit(vars.targetVarbit) ?: 0,
        SolverWorld.varbit(vars.currentVarbit) ?: 0,
        vars.discs,
    )
}

internal enum class DiscColour(val worth: Int) { Red(1), Orange(2), Yellow(3), Green(4), Blue(5), Indigo(6), Violet(7) }

internal enum class DiscShape(val worth: Int) { Circle(1), Triangle(3), Square(4), Pentagon(5) }

internal class Disc(val colour: DiscColour, val shape: DiscShape) {
    val value = colour.worth * shape.worth
    val objId by lazy { Names.obj("eyeglo_${colour.name.lowercase()}_${shape.name.lowercase()}") }
    val code = "${colour.name.first()}${shape.name.first()}"

    override fun toString() = "${colour.name} ${shape.name.lowercase()} ($value)"
}

/** One number on the machine; [current] is what the placed discs add up to so far. */
internal class DiscSlot(val label: String, val target: Int, val current: Int, val discs: Int) {
    val filled get() = target > 0 && current == target
}

private val everyDisc: List<Disc> by lazy {
    DiscColour.entries.flatMap { colour -> DiscShape.entries.map { Disc(colour, it) } }.sortedBy { it.objId }
}

private val discValues: List<Int> by lazy { everyDisc.map { it.value }.distinct().sorted() }

private class Plan(val values: List<Int>, val owned: List<Int>, val missing: List<Int>)

private fun discCount(n: Int) = if (n == 1) "1 disc" else "$n discs"

/** The inventory line, then one line per slot in the order given. [carried] maps obj id to amount. */
internal fun discAdvice(slots: List<DiscSlot>, carried: Map<Int, Int>): List<String> {
    val stock = everyDisc.associateWithTo(LinkedHashMap()) { carried[it.objId] ?: 0 }
    val inventoryLine = stock.filterValues { it > 0 }.let { held ->
        if (held.isEmpty()) "Inventory: (no discs)"
        else "Inventory (${held.values.sum()} discs): " + held.entries.joinToString(", ") { (disc, n) -> "${disc.code}=${disc.value}×$n" }
    }
    val lines = arrayOfNulls<String>(slots.size)
    val waiting = ArrayList<Int>()
    for (index in slots.indices.sortedByDescending { slots[it].discs }) {
        val number = slots[index]
        if (number.filled) {
            lines[index] = "${number.label} [x] done (sum=${number.current} = ${number.target})"
            continue
        }
        val plan = bestPlan(number, stock)
        when {
            plan == null -> lines[index] = noPlanLine(number)
            plan.missing.isEmpty() -> {
                val placed = plan.values.map { takeOne(stock, it) }
                lines[index] = "${header(number)} -> Place ${discCount(number.discs)}: ${placed.joinToString(" + ")}  (sum=${number.target})"
            }
            else -> waiting += index
        }
    }
    for (index in waiting) {
        val number = slots[index]
        lines[index] = bestPlan(number, stock)?.let { shoppingLine(number, it, stock) } ?: noPlanLine(number)
    }
    return listOf(inventoryLine) + lines.map { it.orEmpty() }
}

private fun header(slot: DiscSlot): String {
    val misread = when {
        slot.current == 0 -> ""
        slot.current > slot.target -> "  [reads ${slot.current} - too high, replace discs]"
        else -> "  [reads ${slot.current} - too low, replace discs]"
    }
    return "${slot.label} (${discCount(slot.discs)}): target ${slot.target}$misread"
}

private fun noPlanLine(slot: DiscSlot) =
    "${header(slot)} - no ${slot.discs}-disc combination sums to ${slot.target}. Re-check the displayed number."

private fun shoppingLine(slot: DiscSlot, plan: Plan, stock: MutableMap<Disc, Int>): String {
    val placed = plan.owned.map { takeOne(stock, it) }
    val spares = stock.entries.flatMap { (disc, n) -> List(n) { disc } }
        .sortedWith(compareBy({ it.value }, { it.objId }))
        .take(plan.missing.size)
    spares.forEach { stock[it] = stock.getValue(it) - 1 }
    val wanted = plan.missing.map { value -> everyDisc.first { it.value == value } }
    val fetch = wanted.drop(spares.size)
    val parts = listOfNotNull(
        "${header(slot)} -> need ${discCount(slot.discs)} summing to ${slot.target}.",
        placed.ifNonEmpty { "  Place owned: ${it.joinToString(", ")}." },
        spares.zip(wanted).ifNonEmpty { swaps -> "  Exchange (Oaknock's exchanger): ${swaps.joinToString(", ") { (from, to) -> "$from -> $to" }}." },
        fetch.ifNonEmpty { "  Get more discs from Brimstail, then exchange to: ${it.joinToString(", ")}." },
    )
    return parts.joinToString("")
}

private inline fun <T> List<T>.ifNonEmpty(describe: (List<T>) -> String): String? = if (isEmpty()) null else describe(this)

private fun takeOne(stock: MutableMap<Disc, Int>, value: Int): Disc {
    val disc = stock.entries.first { (disc, n) -> disc.value == value && n > 0 }.key
    stock[disc] = stock.getValue(disc) - 1
    return disc
}

/** Most discs already owned, then least value still to find, then the lowest value list as text. */
private fun bestPlan(slot: DiscSlot, stock: Map<Disc, Int>): Plan? {
    val ownedByValue = HashMap<Int, Int>()
    stock.forEach { (disc, n) -> ownedByValue.merge(disc.value, n, Int::plus) }
    return valueSets(slot.discs, slot.target, 0).map { values ->
        val left = HashMap(ownedByValue)
        val (owned, missing) = values.partition { value ->
            val have = left[value] ?: 0
            (have > 0).also { if (it) left[value] = have - 1 }
        }
        Plan(values, owned, missing)
    }.minWithOrNull(compareBy<Plan>({ -it.owned.size }, { it.missing.sum() }, { it.values.toString() }))
}

/** Every non-decreasing list of [count] disc values, starting from value index [from], adding up to [total]. */
private fun valueSets(count: Int, total: Int, from: Int): List<List<Int>> {
    if (count == 0) return if (total == 0) listOf(listOf()) else listOf()
    return (from until discValues.size).flatMap { at ->
        val value = discValues[at]
        if (value > total) listOf() else valueSets(count - 1, total - value, at).map { listOf(value) + it }
    }
}
