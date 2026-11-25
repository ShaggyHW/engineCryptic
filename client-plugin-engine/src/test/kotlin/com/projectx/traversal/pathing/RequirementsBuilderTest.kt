package com.projectx.traversal.pathing

import com.projectx.traversal.pathing.RequirementsBuilder.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequirementsBuilderTest {

    @Test
    fun `the planner's item keys are ones this builder sends`() {
        for (key in listOf(
            "hasSpiritTreeReRooter", "hasQuestCape", "hasLeelasFavour", "hasLeelasFavourEquiped", "hasPortableObelisk",
            "hasDA4Equiped", "hasJujuTeleportSpiritbag", "hasBigBookOPiracy", "hasNaturesSentinelTeleport",
            "hasRimmingtonTablet", "hasAnachroniaTablet", "hasTaverleyTabletModify", "hasMenaphosTabletModify",
            "hasDramenStaff", "hasMasterFarmerHabitat", "hasScepterOfTheGods", "hasPontifexRing", "hasGhostspeakWorn",
            "hasWickedHoodCharges", "hasWickedHoodAirCharges", "hasWickedHoodAstralCharges", "hasWickedHoodBloodCharges",
        )) {
            assertTrue(RequirementsBuilder.blockOf(key) != null, key)
        }
    }

    @Test
    fun `the keys Java added are all sent`() {
        for (key in listOf(
            "arcSailing", "arcCyclosisUnlocked", "hasScepterOfTheGodsEquiped", "hasMagicalMetorChunk", "hasMemoryStrands",
            "hasHeartTeleport", "hasHazelmereSignet", "hasTokkulZo", "hasTokkulZoEquiped", "arcJournalWaiko", "arcJournalWhalesMaw",
            "arcJournalAminishi", "arcJournalCyclosis", "arcJournalTuaiLeit", "arcJournalTurtles", "arcJournalGoshima", "varbit_15629",
        )) {
            assertTrue(RequirementsBuilder.blockOf(key) != null, key)
        }
    }

    @Test
    fun `what a hit breaks is left out under attack, and direct teleports are not`() {
        for (key in listOf(
            "hasSpiritTreeReRooter", "hasQuestCape", "hasLeelasFavour", "hasBigBookOPiracy", "hasYanilleTabletModify",
            "hasWS4inInv", "hasWickedHoodAirCharges", "varbit_39", "arcSailing", "RedPOA_GamesNecklace",
        )) {
            assertTrue(RequirementsBuilder.skippedUnderAttack(key), key)
        }
        for (key in listOf("hasLeelasFavourEquiped", "hasDA4Equiped", "hasPortableObelisk", "hasYanilleTablet", "hasMasterFarmerHabitat")) {
            assertFalse(RequirementsBuilder.skippedUnderAttack(key), key)
        }
    }

    @Test
    fun `keys sit in Java's blocks`() {
        assertEquals(Block.BASE, RequirementsBuilder.blockOf("hasLegatusPendant"))
        assertEquals(Block.BASE, RequirementsBuilder.blockOf("questDODCoinRealm"))
        assertEquals(Block.IF_SLOT, RequirementsBuilder.blockOf("hasSlayerCape"))
        assertEquals(Block.MENU_ITEM, RequirementsBuilder.blockOf("hasEnchantedLyre"))
        assertEquals(Block.ITEM, RequirementsBuilder.blockOf("hasWickedHood"))
        assertEquals(Block.LODESTONE, RequirementsBuilder.blockOf("varbit_60739"))
        assertEquals(Block.BASE, RequirementsBuilder.blockOf("hasquicktele"))
        for (key in listOf("questUndergroundPass", "TreeGnomeVillageProgress", "MonksFriendQuestProgress", "questHeraldsOfCrimson")) {
            assertEquals(Block.BASE, RequirementsBuilder.blockOf(key), key)
        }
    }

    @Test
    fun `a walk-only profile drops Quick Teleport and the staff ring network`() {
        assertEquals(setOf("hasQuickTele", "fairyRingStaffAccess"), RequirementsBuilder.WALK_ONLY_DROPPED)
    }
}
