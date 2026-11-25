package com.projectx.traversal.nodes

import com.projectx.traversal.hops.HopLearning
import com.projectx.traversal.hops.ScheduleStore
import com.projectx.traversal.hops.ScheduleStore.Sums
import com.projectx.traversal.local.LiveMapFixture.Companion.OX
import com.projectx.traversal.local.LiveMapFixture.Companion.OY
import com.projectx.traversal.local.LiveMapFixture.Companion.t
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the schedule learns: margins, ordinary hops, combo trust, landing holds and interaction times, from the store's sums. */
class ScheduleLearningTest {

    @BeforeTest
    fun setUp() {
        HopLearning.installForTests(null, null)
        ScheduleStore.installForTests(emptyMap(), emptyMap())
    }

    @AfterTest
    fun tearDown() {
        HopLearning.installForTests(null, null)
        ScheduleStore.installForTests(null, null)
    }

    @Test
    fun `margins start at the prior and follow the holds' shortfalls`() {
        assertEquals(4, ScheduleLearning.margin("walk", 4))
        ScheduleStore.installForTests(mapOf("walk" to Sums(15.0, 0.0, 0.0)), null)
        assertTrue(ScheduleLearning.margin("walk", 4) < 4)
        ScheduleStore.installForTests(mapOf("walk" to Sums(15.0, 90.0, 600.0)), null)
        assertTrue(ScheduleLearning.margin("walk", 4) > 5)
        ScheduleStore.installForTests(mapOf("walk" to Sums(100.0, 5000.0, 300000.0)), null)
        assertEquals(ScheduleLearning.MAX_MARGIN.toInt(), ScheduleLearning.margin("walk", 4))
    }

    @Test
    fun `params carry the learned ordinary hop and both margins`() {
        assertEquals(HopSchedule.PRIORS, ScheduleLearning.params(true))
        ScheduleStore.installForTests(mapOf("dive" to Sums(15.0, 105.0, 735.0)), null)
        assertEquals(8, ScheduleLearning.baseGain(false))
        assertEquals(10, ScheduleLearning.baseGain(true))
    }

    @Test
    fun `interaction times come from the learned key, else the default`() {
        assertEquals(3000L, ScheduleLearning.deadMs("ObjectNode:Climb", "ObjectNode", 3000))
        ScheduleStore.installForTests(mapOf("dead:ObjectNode:Climb" to Sums(4.0, 28000.0, 2e8)), null)
        assertEquals(6200L, ScheduleLearning.deadMs("ObjectNode:Climb", "ObjectNode", 3000))
        assertEquals(1, ScheduleLearning.timingCount())
    }

    @Test
    fun `a landing's hold is learned per what sent the player`() {
        assertEquals(1200L, ScheduleLearning.lockMs("NpcNode:Charter", 1200L))
        ScheduleStore.installForTests(mapOf("lock:NpcNode:Charter" to Sums(4.0, 16000.0, 0.0)), null)
        assertEquals(3440L, ScheduleLearning.lockMs("NpcNode:Charter", 1200L))
        assertEquals(1200L, ScheduleLearning.lockMs("teleport", 1200L))
    }

    @Test
    fun `combo spots keep a record and one that mostly misses is split up`() {
        val key = ScheduleLearning.comboKey("dive", t(50, 50), t(42, 42))!!
        assertTrue(key.startsWith("combo-at:dive:") && key.contains("${OX + 50},${OY + 50}"), key)
        assertTrue(ScheduleLearning.comboTrusted(key))
        assertEquals("", ScheduleLearning.comboRecord(key))
        ScheduleStore.installForTests(emptyMap(), mapOf(key to Sums(2.0, 0.0, 0.0)))
        assertFalse(ScheduleLearning.comboTrusted(key))
        assertEquals("0 of 2", ScheduleLearning.comboRecord(key))
        ScheduleStore.installForTests(emptyMap(), mapOf(key to Sums(3.0, 2.0, 2.0)))
        assertTrue(ScheduleLearning.comboTrusted(key))
        assertTrue(ScheduleLearning.comboTrusted(null))
        assertEquals(null, ScheduleLearning.comboKey("surge", null, t(1, 1)))
    }

    @Test
    fun `the combo margin starts from the walk margin`() {
        ScheduleStore.installForTests(emptyMap(), mapOf("walk" to Sums(50.0, 600.0, 7200.0)))
        assertEquals(ScheduleLearning.margin("walk", HopSchedule.HOLD_MARGIN), ScheduleLearning.comboMargin())
    }

    @Test
    fun `only holds for real spots teach the margin`() {
        assertFalse(ScheduleLearning.learnsMargin(16, true))
        assertTrue(ScheduleLearning.learnsMargin(16, false))
        assertFalse(ScheduleLearning.learnsMargin(0, false))
    }

    @Test
    fun `a learning client keeps its samples under the schedule's keys`() {
        val dir = Files.createTempDirectory("schedule-learning")
        try {
            HopLearning.installForTests(dir, "0123456789abcdef")
            ScheduleStore.installForTests(emptyMap(), emptyMap())
            ScheduleLearning.holdDone(beyond = false, keptGain = 14, realGain = 10, owner = "test")
            ScheduleLearning.holdDone(beyond = true, keptGain = 12, realGain = 12, owner = "test")
            ScheduleLearning.cast(surge = true, gain = 9)
            ScheduleLearning.cast(surge = false, gain = 0)
            ScheduleLearning.lock("LodestoneNode", 3000L)
            ScheduleLearning.comboOutcome("combo-at:x", ok = true)
            ScheduleLearning.comboHoldDone(20, 0, "test")
            assertEquals(Sums(1.0, 4.0, 16.0), ScheduleStore.get("walk"))
            assertEquals(Sums(1.0, 0.0, 0.0), ScheduleStore.get("beyond"))
            assertEquals(Sums(1.0, 9.0, 81.0), ScheduleStore.get("surge"))
            assertEquals(0.0, ScheduleStore.get("dive").w)
            assertEquals(3000.0, ScheduleStore.get("lock:LodestoneNode").s1)
            assertEquals(1.0, ScheduleStore.get("combo-at:x").s1)
            assertEquals(20.0, ScheduleStore.get("combo").s1)
        } finally {
            HopLearning.installForTests(null, null)
            dir.toFile().deleteRecursively()
        }
    }
}
