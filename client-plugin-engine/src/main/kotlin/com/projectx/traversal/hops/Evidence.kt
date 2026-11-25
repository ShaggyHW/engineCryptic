package com.projectx.traversal.hops

import com.google.gson.JsonObject
import java.util.TreeSet

/**
 * What has been seen about one key, as counts that add up across clients and contributors. Field names are the
 * store-line names:
 * - [s]/[w]: strong and weak successes; [f]: failures; [sa]/[wa]: successes since the latest failure ([xf], on build
 *   [xb]), by which a demoted key earns its way back;
 * - [ms]/[mf]: strong evidence from hops cast by hand and confirmed; [msa]: manual successes since the latest manual
 *   failure ([mxf]). Once a key has any, the latest manual verdict decides it ([usableEdge]);
 * - [fd]/[ld]: first and last day, [pd]: the day a probe last tested it, [b]: the build last seen on;
 * - [pl]: places (edge keys) a type key was seen at; [c]: contributors.
 *
 * Days are UTC epoch days. Not thread-safe: [HopKnowledge] guards every instance.
 */
internal class Evidence {
    var s = 0
    var w = 0
    var f = 0
    var sa = 0
    var wa = 0
    var ms = 0
    var mf = 0
    var msa = 0
    var fd = 0L
    var ld = 0L
    var xf = 0L
    var pd = 0L
    var mxf = 0L
    var b = ""
    var xb = ""
    val pl = LinkedHashSet<String>()
    val c = TreeSet<String>()

    fun success(strong: Boolean, day: Long, build: String) {
        if (strong) s++ else w++
        if (f > 0) if (strong) sa++ else wa++
        touch(day, build)
    }

    fun failure(day: Long, build: String) {
        f++
        xf = day
        xb = build
        sa = 0
        wa = 0
        touch(day, build)
    }

    fun manualSuccess(day: Long, build: String) {
        ms++
        if (mf > 0) msa++
        touch(day, build)
    }

    fun manualFailure(day: Long, build: String) {
        mf++
        mxf = day
        msa = 0
        touch(day, build)
    }

    fun hasManual(): Boolean = ms + mf > 0

    fun place(edgeKey: String) {
        if (pl.size < MAX_PLACES) pl += edgeKey
    }

    private fun touch(day: Long, build: String) {
        if (fd == 0L || day < fd) fd = day
        if (day > ld) ld = day
        b = build
    }

    /** Folds [o] in: counts add, sets union; "since the latest failure" follows the newer failure. */
    fun add(o: Evidence) {
        s += o.s
        w += o.w
        f += o.f
        if (o.xf > xf) {
            sa = o.sa
            wa = o.wa
            xf = o.xf
            xb = o.xb
        } else if (o.xf == xf) {
            sa += o.sa
            wa += o.wa
        }
        if (o.fd != 0L && (fd == 0L || o.fd < fd)) fd = o.fd
        if (o.ld > ld) {
            ld = o.ld
            b = o.b
        }
        if (o.pd > pd) pd = o.pd
        ms += o.ms
        mf += o.mf
        if (o.mxf > mxf) {
            msa = o.msa
            mxf = o.mxf
        } else if (o.mxf == mxf) {
            msa += o.msa
        }
        o.pl.forEach(::place)
        c += o.c
    }

    fun copy(): Evidence = Evidence().also { it.add(this) }

    /** Usable when the user's own hops say so - one confirmed success, no manual failure after it - else by the learned counts. */
    fun usableEdge(): Boolean {
        if (hasManual()) return ms > 0 && (mf == 0 || msa > 0)
        return if (f == 0) 2 * s + w >= USABLE_SCORE else 2 * sa + wa >= RECOVER_SCORE
    }

    fun usableType(): Boolean {
        val places = pl.size
        val spread = places >= TYPE_PLACES || (c.size >= 2 && places >= 2)
        return spread && (f < 2 || 2 * sa + wa >= RECOVER_SCORE)
    }

    /** Not twice a day, and never once refuted or decided by hand: a failed key stays failed until a manual reset. */
    @Suppress("UNUSED_PARAMETER")
    fun probeAllowed(today: Long, build: String): Boolean = pd != today && f == 0 && !hasManual()

    fun toJson(key: String, contributor: String?): String = buildString(160) {
        append("{\"k\":").append(HopJson.quote(key))
        if (contributor != null) append(",\"c\":").append(HopJson.quote(contributor))
        append(",\"s\":").append(s).append(",\"w\":").append(w).append(",\"f\":").append(f)
        if (sa != 0) append(",\"sa\":").append(sa)
        if (wa != 0) append(",\"wa\":").append(wa)
        append(",\"fd\":").append(fd).append(",\"ld\":").append(ld)
        if (xf != 0L) append(",\"xf\":").append(xf).append(",\"xb\":").append(HopJson.quote(xb))
        if (pd != 0L) append(",\"pd\":").append(pd)
        if (ms != 0) append(",\"ms\":").append(ms)
        if (mf != 0) append(",\"mf\":").append(mf)
        if (msa != 0) append(",\"msa\":").append(msa)
        if (mxf != 0L) append(",\"mxf\":").append(mxf)
        append(",\"b\":").append(HopJson.quote(b))
        if (pl.isNotEmpty()) append(",\"pl\":").append(pl.joinToString(",", "[", "]", transform = HopJson::quote))
        append('}')
    }

    override fun toString(): String =
        "s=$s w=$w f=$f" + (if (hasManual()) " manual $ms/$mf" else "") + (if (pl.isEmpty()) "" else " places=${pl.size}")

    companion object {
        /** Score (2 per strong success, 1 per weak) an edge needs with no failure... */
        const val USABLE_SCORE = 4

        /** ...and after a failure, counting only the successes since. */
        const val RECOVER_SCORE = 6

        /** Type keys need this many distinct places, or two contributors and two places. */
        const val TYPE_PLACES = 3
        private const val MAX_PLACES = 32

        /** The key and evidence of one store line, or null when it is not an evidence line. */
        fun fromJson(o: JsonObject): Pair<String, Evidence>? {
            val key = HopJson.string(o, "k") ?: return null
            val e = Evidence()
            e.s = HopJson.int(o, "s") ?: 0
            e.w = HopJson.int(o, "w") ?: 0
            e.f = HopJson.int(o, "f") ?: 0
            e.sa = HopJson.int(o, "sa") ?: 0
            e.wa = HopJson.int(o, "wa") ?: 0
            e.fd = HopJson.long(o, "fd") ?: 0
            e.ld = HopJson.long(o, "ld") ?: 0
            e.xf = HopJson.long(o, "xf") ?: 0
            e.pd = HopJson.long(o, "pd") ?: 0
            e.ms = HopJson.int(o, "ms") ?: 0
            e.mf = HopJson.int(o, "mf") ?: 0
            e.msa = HopJson.int(o, "msa") ?: 0
            e.mxf = HopJson.long(o, "mxf") ?: 0
            e.b = HopJson.string(o, "b") ?: ""
            e.xb = HopJson.string(o, "xb") ?: ""
            HopJson.string(o, "c")?.let(e.c::add)
            HopJson.array(o, "pl")?.forEach { p -> if (p.isJsonPrimitive && p.asJsonPrimitive.isString) e.place(p.asString) }
            return key to e
        }

        /**
         * The larger of two views of the same evidence, field by field, not their sum: this machine's exported lines and
         * its own local store hold the same hops, and adding them would count every one twice.
         */
        fun atLeast(a: Evidence, b: Evidence): Evidence {
            val e = a.copy()
            e.s = maxOf(a.s, b.s)
            e.w = maxOf(a.w, b.w)
            e.f = maxOf(a.f, b.f)
            if (b.xf > a.xf) {
                e.sa = b.sa
                e.wa = b.wa
                e.xf = b.xf
                e.xb = b.xb
            } else if (b.xf == a.xf) {
                e.sa = maxOf(a.sa, b.sa)
                e.wa = maxOf(a.wa, b.wa)
            }
            if (b.fd != 0L && (e.fd == 0L || b.fd < e.fd)) e.fd = b.fd
            if (b.ld > e.ld) {
                e.ld = b.ld
                e.b = b.b
            }
            if (b.pd > e.pd) e.pd = b.pd
            e.ms = maxOf(a.ms, b.ms)
            e.mf = maxOf(a.mf, b.mf)
            if (b.mxf > a.mxf) {
                e.msa = b.msa
                e.mxf = b.mxf
            } else if (b.mxf == a.mxf) {
                e.msa = maxOf(a.msa, b.msa)
            }
            b.pl.forEach(e::place)
            e.c += b.c
            return e
        }
    }
}
