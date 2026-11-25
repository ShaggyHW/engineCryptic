package com.projectx.quest.runtime

import com.projectx.script.api.varps
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.QuestType

/** Quest completion as the game cache defines it: every progress var of the quest at its completed value. */
internal object CacheQuests {
    @Volatile
    private var byName: Map<String, QuestType>? = null

    fun completed(id: Int): Boolean = Cache.quest(id)?.let(::completed) == true

    /** By the name the quest journal shows, ignoring case, punctuation and a leading "The". */
    fun completed(name: String): Boolean = named(name)?.let(::completed) == true

    fun sameName(a: String, b: String): Boolean = nameKey(a) == nameKey(b)

    /**
     * The raw value of the quest's own progress var, as the route planner's rows compare it: started at its
     * started value, done at its completed value. 0 for a quest with no progress var or none of that name.
     */
    fun progress(name: String): Int {
        val quest = Cache.quests.firstOrNull { q -> q.name?.let { sameName(it, name) } == true && hasProgress(q) } ?: return 0
        return progress(quest)
    }

    /** [progress] of quest [id]: 0 when it has no progress var, -1 when there is no such quest. */
    fun progress(id: Int): Int = Cache.quest(id)?.let(::progress) ?: -1

    /** Journal status of cache quest [id] from its progress vars; [QuestStatus.UNKNOWN] when it has none. */
    internal fun status(id: Int): QuestStatus =
        Cache.quest(id)?.let { status(it, varps::getVar, varps::getVarBit) } ?: QuestStatus.UNKNOWN

    internal fun status(quest: QuestType, varp: (Int) -> Int, varbit: (Int) -> Int): QuestStatus {
        val readings = quest.varpProgress.orEmpty().map { it to varp(it.varId) } +
            quest.varbitProgress.orEmpty().map { it to varbit(it.varId) }
        return when {
            readings.isEmpty() -> QuestStatus.UNKNOWN
            readings.all { (stage, value) -> value >= stage.completedValue } -> QuestStatus.COMPLETED
            else -> if (readings.any { (stage, value) -> value > 0 && value >= stage.startedValue }) QuestStatus.IN_PROGRESS else QuestStatus.NOT_STARTED
        }
    }

    internal fun nameKey(name: String): String = name.lowercase().removePrefix("the ").filter { it.isLetterOrDigit() }

    private fun progress(quest: QuestType): Int {
        quest.varpProgress?.firstOrNull()?.let { return varps.getVar(it.varId) }
        return quest.varbitProgress?.firstOrNull()?.let { varps.getVarBit(it.varId) } ?: 0
    }

    private fun hasProgress(quest: QuestType): Boolean = !quest.varpProgress.isNullOrEmpty() || !quest.varbitProgress.isNullOrEmpty()

    private fun completed(quest: QuestType): Boolean = status(quest, varps::getVar, varps::getVarBit) == QuestStatus.COMPLETED

    private fun named(name: String): QuestType? {
        val all = byName ?: Cache.quests.mapNotNull { quest -> quest.name?.takeIf { it.isNotBlank() }?.let { nameKey(it) to quest } }.toMap()
            .also { if (it.isNotEmpty()) byName = it }
        return all[nameKey(name)]
    }
}
