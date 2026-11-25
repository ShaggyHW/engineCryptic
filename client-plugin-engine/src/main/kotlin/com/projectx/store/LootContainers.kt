package com.projectx.store

import world.gregs.voidps.gameval.Gameval

/** The client's reward and boss-chest containers, by their gameval names: what they gain is loot. Add one to watch it. */
internal object LootContainers {
    private val NAMES = listOf(
        // Treasure trails
        "trail_rewardinv", "drop_casket_rewardinv", "quest_charos_trail_reward_inv",
        // Gates of Elidinis
        "elidinis_reward_chest_inv", "elidinis_boss_reward_chest_inv",
        // Sanctum of Rebirth
        "raid_rebirth1_reward_chest", "raid_rebirth1_boss_reward_chest",
        // Zamorak, Lord of Chaos and the rest of the Elder God Wars Dungeon
        "dungeon_egwd_zamorak_chest_inv", "dungeon_egwd_zamorak_boss_inv",
        "egwd_wen_boss_loot_inv", "egwd_bik_rewards_inv", "egwd_ful_boss_encounter_reward_chest",
        // Amascut, Raksha and other boss chests
        "amascut_boss_reward_chest", "amascut_boss_encounter_reward_chest", "stalker_boss_encounter_reward_chest",
        "gwd2solo_loot_inv", "qbd2_rewards_inv", "raid1_loot_inv", "barrows_inv",
        // Chests and rewards elsewhere
        "crystal_chest_inv", "dungeon_chest_inv", "raptor_chest_inv", "mau_chest_inv", "nomad_hardmode_chest",
        "menaphos_slayer_reward_chest", "sb_reward_chest", "elfcity_slayer_chest_inv", "necro_ritual_reward_inv",
        "trawler_rewardinv", "dom_rewards_inv",
    )

    /** The ids this cache gives those names; a name it lacks is left out. */
    val ids: List<Int> by lazy { NAMES.mapNotNull { Gameval.id(Gameval.INV, it) } }
}
