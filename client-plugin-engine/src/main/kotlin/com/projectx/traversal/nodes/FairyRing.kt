package com.projectx.traversal.nodes

import world.gregs.voidps.type.Tile

/** Fairy ring destinations by their dial code, with the landing tile where it is known. */
enum class FairyRing(val code: String, val tile: Tile?) {
    ZANARIS("aip", Tile.of(2412, 4434, 0)),
    OTHER_REALMS_NARAGI_HOMEWORLD("ais", Tile.of(2030, 5982, 2)),
    ISLANDS_SOUTH_OF_WITCHAVEN("air", Tile.of(2700, 3247, 0)),
    ASGARNIA_MUDSKIPPER_POINT("aiq", Tile.of(2996, 3114, 0)),
    FELDIP_HILLS_NEAR_GUTANOTH("alp", Tile.of(2468, 4189, 0)),
    KANDARIN_MCGRUBORS_WOOD("als", Tile.of(2644, 3495, 0)),
    OTHER_REALMS_ABYSS("alr", Tile.of(3059, 4875, 0)),
    MORYTANIA_HAUNTED_WOODS_EAST_OF_CANIFIS("alq", Tile.of(3597, 3495, 0)),
    FELDIP_HILLS_JUNGLE_HUNTER_AREA("aks", Tile.of(2571, 2956, 0)),
    KANDARIN_PISCATORIS_HUNTER_AREA("akq", null),
    ISLANDS_PENGUINS_NEAR_MISCELLANIA("ajs", null),
    KANDARIN_SLAYER_CAVE_SOUTH_EAST_OF_RELLEKKA("ajr", null),
    DUNGEONS_DARK_CAVE_SOUTH_OF_DORGESH_KAAN("ajq", null),
    ISLANDS_MOS_LEHARMLESS("dip", null),
    MISTHALIN_WIZARDS_TOWER("dis", null),
    OTHER_REALMS_GORAKS_PLANE("dir", null),
    DUNGEONS_MYREQUE_HIDEOUT_UNDER_THE_HOLLOWS("dls", null),
    ISLANDS_POISON_WASTE_SOUTH_OF_ISAFDAR("dlr", null),
    KHARIDIAN_DESERT_NORTH_OF_NARDAH("dlq", null),
    KARAMJA_SOUTH_OF_MUSA_POINT("dkp", null),
    KANDARIN_SNOWY_HUNTER_AREA("dks", null),
    MISTHALIN_EDGEVILLE("dkr", null),
    DUNGEONS_GLACOR_CAVE("dkq", null),
    KANDARIN_TOWER_OF_LIFE("djp", null),
    TIRANNWN_PRIFDDINAS_CLAN_AMLODD("djs", null),
    KANDARIN_SINCLAIR_MANSION_WEST("djr", null),
    ISLANDS_MISCELLANIA("cip", null),
    OTHER_REALMS_SCAPERUNE_EVIL_BOBS_ISLAND("cis", null),
    KANDARIN_NORTH_WEST_OF_YANILLE("ciq", null),
    ISLANDS_SOUTH_OF_DRAYNOR_VILLAGE("clp", null),
    ISLANDS_JUNGLE_SPIDERS_NEAR_YANILLE("cls", null),
    ISLANDS_APE_ATOLL("clr", null),
    OTHER_REALMS_COSMIC_ENTITYS_PLANE("ckp", null),
    MORYTANIA_CANIFIS("cks", null),
    KARAMJA_SOUTH_OF_TAI_BWO_WANNAI_VILLAGE("ckr", null),
    MENAPHOS_IMPERIAL_DISTRICT("ckq", null),
    KARAMJA_KHARAZI_JUNGLE("cjs", null),
    KANDARIN_SINCLAIR_MANSION_EAST("cjr", null),
    ISLANDS_POLYPORE_DUNGEON("bip", null),
    KANDARIN_ARDOUGNE_ZOO_UNICORNS("bis", null),
    OTHER_REALMS_SPARSE_PLANE("bir", null),
    KHARIDIAN_DESERT_NEAR_KALPHITE_HIVE("biq", null),
    DUNGEONS_TZHAAR_AREA("blp", null),
    KANDARIN_LEGENDS_GUILD("blr", null),
    OTHER_REALMS_YUBIUSK("blq", null),
    FELDIP_HILLS_SOUTH_OF_CASTLE_WARS("bkp", null),
    MORYTANIA_MORT_MYRE_SOUTH_OF_CANIFIS("bkr", null),
    OTHER_REALMS_ENCHANTED_VALLEY("bkq", null),
    FORT_FORINTHRY("bjp", null),
    THE_LOST_GROVE("bjs", null),
    OTHER_REALMS_REALM_OF_THE_FISHER_KING("bjr", null),
    DUNGEONS_ANCIENT_CAVERN("bjq", null),
    HEAVENHYTHE("bks", null);

    companion object {
        @JvmStatic
        fun forCode(code: String?): FairyRing? = code?.lowercase()?.let { c -> entries.firstOrNull { it.code == c } }
    }
}
