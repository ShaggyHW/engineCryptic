package com.projectx.scheduler

import com.google.gson.Gson
import com.google.gson.JsonObject as GsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.serialization.json.JsonPrimitive

/**
 * One script in a plan, run for a random while between [minMinutes] and [maxMinutes], with [settings] (stored setting
 * keys to values) on top of the ones the player saved for it.
 */
internal data class PlanStep(
    val script: String,
    val minMinutes: Int,
    val maxMinutes: Int,
    val settings: Map<String, JsonPrimitive> = emptyMap(),
)

/**
 * Scripts run one after another, each for its own random while, with a short random pause between them. A plan that
 * [repeat]s starts over after its last step; one that does not stops there. The launcher writes it.
 */
internal data class ScriptPlan(
    val name: String,
    val steps: List<PlanStep>,
    val repeat: Boolean = false,
    val pauseMinSeconds: Int = DEFAULT_PAUSE_MIN_SECONDS,
    val pauseMaxSeconds: Int = DEFAULT_PAUSE_MAX_SECONDS,
) {
    // Gson fills fields without running a constructor, so anything left out reads as null here, never as a default.
    private class RawStep(
        val script: String?,
        @SerializedName("min_minutes") val minMinutes: Int?,
        @SerializedName("max_minutes") val maxMinutes: Int?,
        val settings: GsonObject?,
    )

    private class RawPlan(
        val name: String?,
        val steps: List<RawStep?>?,
        val repeat: Boolean?,
        @SerializedName("pause_min_seconds") val pauseMinSeconds: Int?,
        @SerializedName("pause_max_seconds") val pauseMaxSeconds: Int?,
    )

    companion object {
        const val DEFAULT_PAUSE_MIN_SECONDS = 15
        const val DEFAULT_PAUSE_MAX_SECONDS = 90
        private const val MAX_MINUTES = 24 * 60
        private const val MAX_PAUSE_SECONDS = 10 * 60

        /** Booleans, numbers and text only: what a setting can hold. */
        private fun settingsOf(raw: GsonObject?): Map<String, JsonPrimitive> =
            raw?.entrySet()?.mapNotNull { (key, value) ->
                val p = value?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return@mapNotNull null
                key to when {
                    p.isBoolean -> JsonPrimitive(p.asBoolean)
                    p.isNumber -> JsonPrimitive(p.asLong)
                    else -> JsonPrimitive(p.asString)
                }
            }?.toMap().orEmpty()

        /** The plan in [json], or why it cannot run. */
        fun parse(json: String): Result<ScriptPlan> = runCatching {
            val raw = Gson().fromJson(json, RawPlan::class.java) ?: error("empty plan")
            val steps = raw.steps.orEmpty().filterNotNull().mapNotNull { step ->
                val script = step.script?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val min = step.minMinutes ?: error("$script needs a shortest run")
                val max = step.maxMinutes ?: min
                require(min in 1..MAX_MINUTES && max in min..MAX_MINUTES) {
                    "$script must run between 1 minute and 24 hours, with the shortest no longer than the longest"
                }
                PlanStep(script, min, max, settingsOf(step.settings))
            }
            require(steps.isNotEmpty()) { "a plan needs at least one script" }
            val pauseMin = raw.pauseMinSeconds ?: DEFAULT_PAUSE_MIN_SECONDS
            val pauseMax = raw.pauseMaxSeconds ?: maxOf(pauseMin, DEFAULT_PAUSE_MAX_SECONDS)
            require(pauseMin in 0..MAX_PAUSE_SECONDS && pauseMax in pauseMin..MAX_PAUSE_SECONDS) {
                "the pause between scripts must be between 0 and 10 minutes"
            }
            ScriptPlan(raw.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Plan", steps, raw.repeat ?: false, pauseMin, pauseMax)
        }
    }
}
