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

    /** A drop the game itself announced in chat; [news] when it came from the player's own News broadcast. */
    data class AnnouncedDrop(val itemName: String, val quantity: Int, val news: Boolean)

    private val MARKUP = Regex("<[^>]*>")
    private val RECEIVE = Regex("""\bYou receive: ([\d,]+) x (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val NEWS = Regex("""^News: (.+?) has received an? (.+?) drop!?$""", RegexOption.IGNORE_CASE)

    /** A News broadcast reaches everyone in the world, so it only counts when it names [playerName]. */
    fun announcedDrop(message: String, playerName: String?): AnnouncedDrop? {
        val text = MARKUP.replace(message, "").replace(NBSP, ' ').trim()
        RECEIVE.find(text)?.let { match ->
            val quantity = match.groupValues[1].replace(",", "").toIntOrNull() ?: return null
            return AnnouncedDrop(match.groupValues[2].trim(), quantity, news = false)
        }
        val news = NEWS.matchEntire(text) ?: return null
        if (playerName == null || !sameName(news.groupValues[1], playerName)) return null
        return AnnouncedDrop(news.groupValues[2].trim(), 1, news = true)
    }

    private fun sameName(a: String, b: String): Boolean = normalName(a) == normalName(b)

    private fun normalName(name: String) = name.replace(NBSP, ' ').replace('_', ' ').trim().lowercase()

    private const val NBSP = '\u00A0'

    /** Item id to how many more of it [after] holds than [before]; only the ones that grew. */
    fun gained(before: Map<Int, Int>, after: Map<Int, Int>): Map<Int, Int> =
        after.mapNotNull { (id, count) -> (count - (before[id] ?: 0)).takeIf { it > 0 }?.let { id to it } }.toMap()
}
