package com.projectx.script.api

/**
 * The names of the NPCs within [range] tiles that offer Attack, once each ignoring case and sorted, for a settings
 * list of what to fight. Unnamed NPCs, and the many whose name reads "null", are left out.
 */
@JvmOverloads
fun attackableNpcNamesNearby(range: Int = 20): List<String> =
    AttackableNames.of(allNpcsWithinRange(range).map { it.name() to it.hasOption("Attack") })

internal object AttackableNames {
    fun of(npcs: Iterable<Pair<String?, Boolean>>): List<String> = npcs
        .filter { (_, attackable) -> attackable }
        .mapNotNull { (name, _) -> name?.trim() }
        .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        .distinctBy(String::lowercase)
        .sortedBy(String::lowercase)
}
