package com.projectx.script.api

import com.projectx.quest.runtime.CacheQuests

/** Quest state as the game cache defines it, read live from the quest's progress vars. */
object Quests {
    @JvmStatic
    fun isCompleted(id: Int): Boolean = CacheQuests.completed(id)

    /** By the name the quest journal shows, ignoring case, punctuation and a leading "The". */
    @JvmStatic
    fun isCompleted(name: String): Boolean = CacheQuests.completed(name)

    /** The raw value of quest [id]'s progress var, from its started value to its completed one; -1 for no such quest. */
    @JvmStatic
    fun progress(id: Int): Int = CacheQuests.progress(id)

    @JvmStatic
    fun progress(name: String): Int = CacheQuests.progress(name)
}
