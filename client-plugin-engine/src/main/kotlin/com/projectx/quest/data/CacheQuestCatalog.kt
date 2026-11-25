package com.projectx.quest.data

import com.projectx.quest.runtime.CacheQuests
import org.projectx.core.game.skill.Skill
import world.gregs.voidps.cache.type.data.QuestType
import world.gregs.voidps.gameval.Gameval

/**
 * Turns the game cache's quest types into guide-less [Quest]s, so quests released after the bundled guides still
 * list with their real name, requirements and status.
 */
internal object CacheQuestCatalog {
    private const val QUEST_GAMEVALS = "quest"

    /** The cache's quest_length param, 1 through 9, in the words the bundled guides use. */
    private val LENGTH_WORDS = listOf(
        "veryshort", "short", "shortmedium", "medium", "mediumlong", "long", "longverylong", "verylong", "veryverylong",
    )

    /** Jagex names every tutorial entry tut*, so these never count as quests (tut5, tutorial_3, quest_tut6...). */
    private val TUTORIAL_NAME = Regex("^(quest_)?tut")

    /** Param ids for the quest params this catalogue reads; null when the gameval dictionaries are missing. */
    class QuestParams(val disabled: Int?, val releaseYear: Int?, val length: Int?) {
        companion object {
            fun fromGamevals() = QuestParams(
                disabled = Gameval.id(Gameval.PARAM, "quest_disabled"),
                releaseYear = Gameval.id(Gameval.PARAM, "quest_release_year"),
                length = Gameval.id(Gameval.PARAM, "quest_length"),
            )
        }
    }

    fun withoutGuides(
        types: List<QuestType>,
        guides: List<Quest>,
        params: QuestParams = QuestParams.fromGamevals(),
        devName: (Int) -> String? = { Gameval.name(QUEST_GAMEVALS, it) },
    ): List<Quest> {
        val guidedIds = guides.mapTo(HashSet()) { it.cacheQuestId }
        val guidedNames = guides.mapTo(HashSet()) { CacheQuests.nameKey(it.name) }
        val takenSlugs = guides.mapTo(HashSet()) { it.slug }
        val namesById = types.associate { it.id to it.name }
        val listedNames = HashSet<String>()
        return types
            .filter { isRealQuest(it, params, devName) }
            .filterNot { it.id in guidedIds || CacheQuests.nameKey(it.name!!) in guidedNames }
            .filter { listedNames.add(CacheQuests.nameKey(it.name!!)) }
            .map { type ->
                val slug = slugFor(type.name!!).let { if (takenSlugs.add(it)) it else "$it-${type.id}".also(takenSlugs::add) }
                stub(type, slug, params, namesById)
            }
    }

    fun isRealQuest(type: QuestType, params: QuestParams, devName: (Int) -> String?): Boolean {
        if (type.name.isNullOrBlank()) return false
        if (type.varpProgress.isNullOrEmpty() && type.varbitProgress.isNullOrEmpty()) return false
        if (params.disabled != null && type.intParam(params.disabled).let { it != null && it != 0 }) return false
        if (params.releaseYear != null && type.params?.containsKey(params.releaseYear) != true) return false
        return devName(type.id)?.let { TUTORIAL_NAME.containsMatchIn(it) } != true
    }

    private fun stub(type: QuestType, slug: String, params: QuestParams, namesById: Map<Int, String?>) = Quest(
        slug = slug,
        name = type.name!!,
        // unknown8 matches the guides' members flag on 336 of the 339 quests both describe.
        members = type.unknown8,
        length = params.length?.let { type.intParam(it) }?.let { LENGTH_WORDS.getOrNull(it - 1) },
        prereqQuests = type.questRequirements.orEmpty().mapNotNull { namesById[it]?.takeIf(String::isNotBlank) }.distinct(),
        questReqs = requirementsOf(type),
        cacheQuestId = type.id,
    )

    private fun requirementsOf(type: QuestType): List<QuestReq> = buildList {
        for (req in type.skillRequirements.orEmpty()) {
            val skill = Skill.byId(req.skill) ?: continue
            add(QuestReq("skill", skillName(skill), req.level))
        }
        if (type.questPointRequirement > 0) add(QuestReq("questpoints", level = type.questPointRequirement))
    }

    private fun skillName(skill: Skill): String =
        if (skill == Skill.DEFENSE) "Defence" else skill.name.lowercase().replaceFirstChar(Char::uppercaseChar)

    fun slugFor(name: String): String =
        name.lowercase().replace("'", "").replace("’", "").split(Regex("[^a-z0-9]+")).filter(String::isNotEmpty).joinToString("-").ifEmpty { "quest" }

    private fun QuestType.intParam(id: Int): Int? = (params?.get(id) as? Number)?.toInt()
}
