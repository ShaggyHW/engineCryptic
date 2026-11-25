package com.projectx.store

/** What makes a drop worth telling the site about. The site prices and checks every drop again; this only saves calls. */
internal object RareDropRules {
    /** The site's RS3 minimum for the whole stack. */
    const val MIN_VALUE_GP = 5_000_000L

    private val NOTABLE = Regex("^((sealed )?clue scroll|reward casket) \\((hard|elite|master)\\)$")

    /** Posted whatever it is worth: clue scrolls from hard up, sealed or not, and their reward caskets. */
    fun isNotable(name: String): Boolean = NOTABLE.matches(name.trim().lowercase())

    fun worthReporting(name: String, stackValueGp: Long?): Boolean =
        (stackValueGp != null && stackValueGp >= MIN_VALUE_GP) || isNotable(name)

    enum class PetMessage { FOLLOWING, BACKPACK, DUPLICATE }

    private val PET_MESSAGES = listOf(
        "You have a funny feeling like you're being followed" to PetMessage.FOLLOWING,
        "You feel something weird sneaking into your backpack" to PetMessage.BACKPACK,
        "You have a funny feeling like you would have been followed" to PetMessage.DUPLICATE,
    )

    fun petMessage(message: String): PetMessage? = PET_MESSAGES.firstOrNull { message.startsWith(it.first) }?.second

    /** Item id to how many more of it [after] holds than [before]; only the ones that grew. */
    fun gained(before: Map<Int, Int>, after: Map<Int, Int>): Map<Int, Int> =
        after.mapNotNull { (id, count) -> (count - (before[id] ?: 0)).takeIf { it > 0 }?.let { id to it } }.toMap()
}
