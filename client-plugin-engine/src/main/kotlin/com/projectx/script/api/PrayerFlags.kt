package com.projectx.script.api

/**
 * The varbit that says each prayer is on, by Jagex's name for it. The game's prayer data names every prayer but does
 * not point at its flag: the server sets one bit per prayer, and a tiered prayer's tiers share one.
 */
internal object PrayerFlags {

    private val normal: Map<String, String> = buildMap {
        fun tiers(flag: String, vararg names: String) = names.forEach { put(it, "combatv2_prayer_$flag") }
        tiers("boost_defence", "Thick Skin", "Rock Skin", "Steel Skin")
        tiers("boost_melee_strength", "Burst of Strength", "Superhuman Strength", "Ultimate Strength")
        tiers("boost_melee_attack", "Clarity of Thought", "Improved Reflexes", "Incredible Reflexes")
        tiers("boost_ranged_strength", "Unstoppable Force", "Unrelenting Force", "Overpowering Force")
        tiers("boost_ranged_attack", "Sharp Eye", "Hawk Eye", "Eagle Eye")
        tiers("boost_magic_strength", "Charge", "Super Charge", "Overcharge")
        tiers("boost_magic_attack", "Mystic Will", "Mystic Lore", "Mystic Might")
        tiers("boost_necromancy_strength", "Decay", "Hastened Decay", "Accelerated Decay")
        tiers("boost_necromancy_attack", "Hand of Judgement", "Hand of Fate", "Hand of Doom")
        tiers("rapid_restore", "Rapid Restore")
        tiers("heal", "Rapid Heal")
        tiers("protect_item", "Protect Item")
        tiers("protect_summoning", "Protect from Summoning")
        tiers("protect_mage", "Protect from Magic")
        tiers("protect_range", "Protect from Ranged")
        tiers("protect_melee", "Protect from Melee")
        tiers("protect_necromancy", "Protect from Necromancy")
        tiers("retribution", "Retribution")
        tiers("redemption", "Redemption")
        tiers("smite", "Smite")
        tiers("chivalry", "Chivalry")
        tiers("rapid_renewal", "Rapid Renewal")
        tiers("piety", "Piety")
        tiers("rigour", "Rigour")
        tiers("augury", "Augury")
        tiers("sanctity", "Sanctity")
        tiers("eclipsed_soul", "Eclipsed Soul")
        tiers("divine_rage", "Divine Rage")
    }

    private val curses: Map<String, String> = mapOf(
        "Protect Item" to "curse_protect_item",
        "Sap Melee Attack" to "curse_sap_warrior",
        "Sap Ranged Attack" to "curse_sap_ranger",
        "Sap Ranged Strength" to "curse_sap_range_str",
        "Sap Magic Attack" to "curse_sap_mage",
        "Sap Magic Strength" to "curse_sap_magic_str",
        "Sap Adrenaline" to "curse_sap_spirit",
        "Sap Defence" to "curse_sap_defence",
        "Sap Melee Strength" to "curse_sap_melee_str",
        "Berserker" to "curse_berserker",
        "Sap Necromancy Attack" to "curse_sap_necromancy_attack",
        "Sap Necromancy Strength" to "curse_sap_necromancy_strength",
        "Deflect Summoning" to "curse_deflect_summoning",
        "Deflect Magic" to "curse_deflect_magic",
        "Deflect Ranged" to "curse_deflect_ranged",
        "Deflect Melee" to "curse_deflect_melee",
        "Deflect Necromancy" to "curse_deflect_necromancy",
        "Leech Melee Attack" to "curse_leech_attack",
        "Leech Ranged Attack" to "curse_leech_ranged",
        "Leech Ranged Strength" to "curse_leech_ranged_strength",
        "Leech Magic Attack" to "curse_leech_magic",
        "Leech Magic Strength" to "curse_leech_magic_strength",
        "Leech Defence" to "curse_leech_defence",
        "Light Form" to "curse_lightform",
        "Dark Form" to "curse_darkform",
        "Leech Melee Strength" to "curse_leech_strength",
        "Leech Necromancy Attack" to "curse_leech_necromancy_attack",
        "Leech Run Energy" to "curse_leech_energy",
        "Leech Necromancy Strength" to "curse_leech_necromancy_strength",
        "Leech Adrenaline" to "curse_leech_special_attack",
        "Chronicle Attraction" to "curse_chronicle_attraction",
        "Soul Link" to "curse_soullink",
        "Wrath" to "curse_wrath",
        "Teamwork Protection" to "curse_teamwork_protection",
        "Superheat Form" to "curse_superheat_form",
        "Soul Split" to "curse_soulsplit",
        "Fortitude" to "curse_fortitude",
        "Turmoil" to "curse_turmoil",
        "Anguish" to "curse_anguish",
        "Torment" to "curse_torment",
        "Sorrow" to "curse_sorrow",
        // The upgraded curses are drawn as overheads, and the server flags them as such.
        "Malevolence" to "curse_overhead_turmoil",
        "Desolation" to "curse_overhead_anguish",
        "Affliction" to "curse_overhead_torment",
        "Ruination" to "curse_ruination",
    )

    fun varbitName(book: PrayerBook, prayer: String): String? = when (book) {
        PrayerBook.NORMAL -> normal[prayer]
        PrayerBook.CURSES -> curses[prayer]
    }

    internal fun entries(book: PrayerBook): Map<String, String> = if (book == PrayerBook.NORMAL) normal else curses
}
