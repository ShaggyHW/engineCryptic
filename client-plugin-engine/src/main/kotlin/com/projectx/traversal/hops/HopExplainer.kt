package com.projectx.traversal.hops

import com.projectx.pathfinder.StepRule
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile

/**
 * Explains a recorded hop against its own window. The hop is re-run from every tile the player may have fired it
 * from, with and without the edges learned so far: both agreeing with the landing is nothing new; only the learned
 * overlay agreeing confirms its edges; only the plain map agreeing means the learned edges it used failed; neither
 * means an overshoot (which blocked steps, opened, reproduce the landing), an undershoot (the next step stops hops)
 * or a refusal on an open path.
 */
internal object HopExplainer {

    enum class Kind { MATCH, LEARNED_OK, LEARNED_FAILED, PASS, STOP, REFUSED, NO_CAST, UNEXPLAINED }

    data class Result(
        val kind: Kind,
        val pass: List<Long> = emptyList(),
        val stop: List<Long> = emptyList(),
        val failed: List<Long> = emptyList(),
        val confirmed: List<Long> = emptyList(),
        val strong: Boolean = false,
        val note: String = "",
    ) {
        override fun toString(): String = buildString {
            append(kind.name.lowercase())
            if (note.isNotEmpty()) append(" ($note)")
            list(if (strong) "strong pass" else "weak pass", pass)
            list("stop", stop)
            list("failed", failed)
            list("confirmed", confirmed)
        }

        private fun StringBuilder.list(what: String, edges: List<Long>) {
            if (edges.isNotEmpty()) append("; ").append(what).append(' ').append(edges.joinToString(", ", transform = HopEdges::describe))
        }
    }

    const val MAX_SINGLE = 120
    const val MAX_PAIR = 30
    const val MAX_EXPLAINING = 6
    const val ARM_STEPS = 2
    const val POST_STEPS = 2

    data class Spec(val ability: String, val direction: Direction?, val aim: Tile?)

    fun explain(o: HopObservation): Result {
        if (o.outcome == NO_CAST) return Result(Kind.NO_CAST, note = "the cast did not happen")
        val observed = observedStarts(o)
        val starts = withArming(o, observed)
        val post = if (o.held) 0 else POST_STEPS
        val clean = o.held && observed.size == 1
        val learnedPass = o.learnedPass.toHashSet()
        val learnedStop = o.learnedStop.toHashSet()
        val plain = o.rule(null, null)
        val learned = o.rule(learnedPass, learnedStop)
        val actual = o.landed
        val spec = Spec(o.ability, o.direction, o.aim)

        val plainStart = matchingStart(spec, plain, starts, actual, post, o)
        val learnedStart = matchingStart(spec, learned, starts, actual, post, o)
        if (plainStart != null && learnedStart != null) return Result(Kind.MATCH)

        if (plainStart == null && learnedStart == null) {
            otherWay(o, starts, post, plain, learned)?.let { d ->
                return Result(Kind.MATCH, note = "surged ${HopEdges.name(d)}, not the expected ${o.direction?.let(HopEdges::name)}")
            }
        }

        if (learnedStart != null) {
            val used = usedEdges(spec, learned, learnedStart, o, learnedPass)
            return Result(Kind.LEARNED_OK, confirmed = used, strong = clean && used.size == 1)
        }
        if (plainStart != null) {
            if (!clean || plainStart !in observed) return Result(Kind.UNEXPLAINED, note = "the plain map fits only with an uncertain start or landing")
            val failed = usedEdges(spec, learned, plainStart, o, learnedPass).toMutableList()
            val path = ArrayList<Tile>()
            run(spec, plain, plainStart, path)
            for (e in steps(plainStart, path)) if (e in learnedStop && e !in failed) failed += e
            return Result(Kind.LEARNED_FAILED, failed = failed, strong = true)
        }

        val cast = observed.first()
        if (actual == null) {
            if (!clean) return Result(Kind.UNEXPLAINED, note = "refused, start uncertain")
            val path = ArrayList<Tile>()
            run(spec, learned, cast, path)
            val used = usedEdges(spec, learned, cast, o, learnedPass)
            if (used.isNotEmpty()) return Result(Kind.LEARNED_FAILED, failed = used, strong = true)
            return Result(Kind.REFUSED, stop = steps(cast, path), note = "refused on an open path")
        }

        if (clean) {
            val path = ArrayList<Tile>()
            run(spec, learned, cast, path)
            val k = if (cast == actual) -1 else path.indexOf(actual)
            if ((k >= 0 || cast == actual) && k < path.size - 1) {
                val a = if (k < 0) cast else path[k]
                val b = path[k + 1]
                if (o.occupiedAt(b.x, b.y)) return Result(Kind.UNEXPLAINED, note = "stopped before an NPC at $b")
                val d = HopModel.direction(b.x - a.x, b.y - a.y) ?: return Result(Kind.UNEXPLAINED, note = "no step")
                return Result(Kind.STOP, stop = listOf(HopEdges.pack(a.x, a.y, a.plane, d)), strong = true)
            }
        }

        val candidates = LinkedHashSet<Long>()
        for (start in starts) candidates += refusedSteps(spec, o, learnedPass, learnedStop, start)
        candidates.removeAll(learnedPass)
        val asked = candidates.toList().take(MAX_SINGLE)
        val explaining = asked.filter { c ->
            matchingStart(spec, o.rule(learnedPass + c, learnedStop), starts, actual, post, o) != null
        }
        if (explaining.size == 1) {
            return Result(Kind.PASS, pass = explaining, strong = clean, note = if (clean) "" else "start or landing uncertain: weak")
        }
        if (explaining.isNotEmpty()) {
            val first = firstTile(explaining, asked)
            if (first.size <= MAX_EXPLAINING) return Result(Kind.PASS, pass = first, note = "${explaining.size} edges fit, took the first met")
            return Result(Kind.UNEXPLAINED, note = "${explaining.size} single edges would explain it")
        }

        val inPairs = LinkedHashSet<Long>()
        val few = asked.take(MAX_PAIR)
        for (i in few.indices) for (j in i + 1 until few.size) {
            if (matchingStart(spec, o.rule(learnedPass + few[i] + few[j], learnedStop), starts, actual, post, o) != null) {
                inPairs += few[i]
                inPairs += few[j]
            }
        }
        if (inPairs.isNotEmpty() && inPairs.size <= MAX_EXPLAINING) return Result(Kind.PASS, pass = inPairs.toList(), note = "needs two edges")
        return Result(Kind.UNEXPLAINED, note = "no edge or pair explains the landing")
    }

    private fun observedStarts(o: HopObservation): List<Tile> = o.starts.ifEmpty { listOfNotNull(o.from) }

    /** A surge whose landing fits neither map along its facing but fits another of the 8 ways: that way, else null. */
    fun otherWay(o: HopObservation): Direction? {
        if (o.ability != SURGE || o.landed == null) return null
        val starts = withArming(o, observedStarts(o))
        val post = if (o.held) 0 else POST_STEPS
        val learnedPass = o.learnedPass.toHashSet()
        val learnedStop = o.learnedStop.toHashSet()
        val plain = o.rule(null, null)
        val learned = o.rule(learnedPass, learnedStop)
        val spec = Spec(o.ability, o.direction, o.aim)
        if (matchingStart(spec, plain, starts, o.landed, post, o) != null || matchingStart(spec, learned, starts, o.landed, post, o) != null) return null
        return otherWay(o, starts, post, plain, learned)
    }

    private fun otherWay(o: HopObservation, starts: List<Tile>, post: Int, plain: StepRule, learned: StepRule): Direction? {
        val actual = o.landed
        if (o.ability != SURGE || actual == null) return null
        return HopModel.DIRECTIONS.firstOrNull { d ->
            if (d == o.direction) return@firstOrNull false
            val other = Spec(o.ability, d, o.aim)
            matchingStart(other, plain, starts, actual, post, o) != null || matchingStart(other, learned, starts, actual, post, o) != null
        }
    }

    /**
     * A probe really tested its edge and the edge held: a clean hop, opening the edge would have changed where the hop
     * goes from the cast tile, and it went where the map without it says. A probe that went round its edge tested nothing.
     */
    fun probeRefuted(o: HopObservation): Boolean {
        if (!o.probe || o.probeEdge < 0 || !o.held || o.starts.size > 1) return false
        val cast = o.starts.firstOrNull() ?: o.from ?: return false
        val learnedPass = o.learnedPass.toHashSet()
        val learnedStop = o.learnedStop.toHashSet()
        val spec = Spec(o.ability, o.direction, o.aim)
        val without = run(spec, o.rule(learnedPass, learnedStop), cast)
        val with = run(spec, o.rule(learnedPass + o.probeEdge, learnedStop), cast)
        return !sameResult(without, with, cast) && sameResult(without, o.landed, cast)
    }

    /**
     * Two results agree. "No move" is one result whether the game refused the cast or cast it and the player stayed on
     * the cast tile: a far dive whose first step is blocked is cast and moves 0 tiles where the model says null.
     */
    fun sameResult(a: Tile?, b: Tile?, cast: Tile): Boolean = a == b || (a == null || a == cast) && (b == null || b == cast)

    fun withArming(o: HopObservation, observed: List<Tile>): List<Tile> {
        val out = LinkedHashSet(observed)
        for (start in observed) out += walkable(o, start, ARM_STEPS)
        return out.toList()
    }

    /** Tiles within [depth] recorded walking steps of [from], [from] left out. */
    fun walkable(o: HopObservation, from: Tile, depth: Int): List<Tile> {
        val out = ArrayList<Tile>()
        var frontier = listOf(from)
        val seen = hashSetOf(from)
        repeat(depth) {
            val next = ArrayList<Tile>()
            for (t in frontier) for (d in HopModel.DIRECTIONS) {
                if (!o.walkStep(t.x, t.y, t.plane, d)) continue
                val n = Tile.of(t.x + d.delta.x, t.y + d.delta.y, t.plane)
                if (seen.add(n)) {
                    next += n
                    out += n
                }
            }
            frontier = next
        }
        return out
    }

    fun run(spec: Spec, rule: StepRule, start: Tile, path: MutableList<Tile>? = null): Tile? =
        if (spec.ability == SURGE) HopModel.surge(rule, start, spec.direction ?: Direction.NONE, path)
        else spec.aim?.let { HopModel.dive(rule, start, it, path) }

    fun steps(start: Tile, path: List<Tile>): List<Long> {
        val out = ArrayList<Long>()
        var a = start
        for (b in path) {
            HopModel.direction(b.x - a.x, b.y - a.y)?.let { out += HopEdges.pack(a.x, a.y, a.plane, it) }
            a = b
        }
        return out
    }

    private fun matchingStart(spec: Spec, rule: StepRule, starts: List<Tile>, actual: Tile?, post: Int, o: HopObservation): Tile? =
        starts.firstOrNull { start ->
            val r = run(spec, rule, start)
            if (actual == null) r == null else r != null && (actual == r || post > 0 && actual in walkable(o, r, post))
        }

    private fun usedEdges(spec: Spec, learned: StepRule, start: Tile, o: HopObservation, learnedPass: Set<Long>): List<Long> {
        val path = ArrayList<Tile>()
        run(spec, learned, start, path)
        val out = ArrayList<Long>()
        var a = start
        for (b in path) {
            val d = HopModel.direction(b.x - a.x, b.y - a.y)
            if (d != null && !o.walkStep(a.x, a.y, a.plane, d)) {
                val e = HopEdges.pack(a.x, a.y, a.plane, d)
                if (e in learnedPass && e !in out) out += e
            }
            a = b
        }
        return out
    }

    private fun refusedSteps(spec: Spec, o: HopObservation, learnedPass: Set<Long>, learnedStop: Set<Long>, start: Tile): Set<Long> {
        val out = LinkedHashSet<Long>()
        val base = o.rule(learnedPass, learnedStop)
        val recording = StepRule { x, y, p, d ->
            val ok = base.canStep(x, y, p, d)
            if (!ok && o.known(x, y) && o.known(x + d.delta.x, y + d.delta.y)) out += HopEdges.pack(x, y, p, d)
            ok
        }
        run(spec, recording, start)
        return out
    }

    private fun firstTile(explaining: List<Long>, asked: List<Long>): List<Long> {
        val first = asked.firstOrNull { it in explaining } ?: return explaining
        return explaining.filter { it and 0xFL.inv() == first and 0xFL.inv() }
    }

    const val SURGE = "surge"
    const val DIVE = "dive"
    const val NO_CAST = "no_cast"
    const val LANDED = "landed"
    const val REFUSED = "refused"
}
