package com.projectx.puzzle.spec

import com.projectx.puzzle.combolock.CombinationLock
import com.projectx.puzzle.combolock.CombinationLockInterface
import com.projectx.puzzle.combolock.CombinationLockLayout
import com.projectx.puzzle.combolock.DialMove
import com.projectx.puzzle.combolock.TurnDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CombinationLockSpecTest {

    @Test
    fun `a dial already on its letter needs no clicks`() {
        for (letter in 'A'..'Z') {
            val move = CombinationLock.move(letter, letter)
            assertEquals(0, move.clicks)
            assertTrue(move.solved)
            assertEquals(TurnDirection.FORWARD, move.direction)
        }
    }

    @Test
    fun `the shorter way round the alphabet is chosen`() {
        assertEquals(DialMove(TurnDirection.FORWARD, 1), CombinationLock.move('M', 'N'))
        assertEquals(DialMove(TurnDirection.BACKWARD, 1), CombinationLock.move('N', 'M'))
        assertEquals(DialMove(TurnDirection.FORWARD, 2), CombinationLock.move('Y', 'A'))
        assertEquals(DialMove(TurnDirection.BACKWARD, 2), CombinationLock.move('B', 'Z'))
        assertEquals(DialMove(TurnDirection.FORWARD, 12), CombinationLock.move('C', 'O'))
        assertEquals(DialMove(TurnDirection.BACKWARD, 12), CombinationLock.move('O', 'C'))
    }

    @Test
    fun `half way round in either direction turns forward`() {
        assertEquals(DialMove(TurnDirection.FORWARD, 13), CombinationLock.move('B', 'O'))
        assertEquals(DialMove(TurnDirection.FORWARD, 13), CombinationLock.move('O', 'B'))
    }

    @Test
    fun `letters are compared without regard to case`() {
        assertEquals(CombinationLock.move('F', 'K'), CombinationLock.move('f', 'k'))
        assertEquals(DialMove(TurnDirection.FORWARD, 0), CombinationLock.move('q', 'Q'))
    }

    @Test
    fun `every pair of letters is reached in at most thirteen clicks in the stated direction`() {
        for (from in 'A'..'Z') for (to in 'A'..'Z') {
            val move = CombinationLock.move(from, to)
            assertTrue(move.clicks in 0..13)
            val step = if (move.direction == TurnDirection.FORWARD) 1 else -1
            val landed = 'A' + Math.floorMod(from - 'A' + step * move.clicks, 26)
            assertEquals(to, landed, "$from -> $to via $move")
        }
    }

    @Test
    fun `dial moves are value objects`() {
        val move = DialMove(TurnDirection.BACKWARD, 4)
        assertEquals(DialMove(TurnDirection.BACKWARD, 4), move)
        assertEquals(DialMove(TurnDirection.FORWARD, 4), move.copy(direction = TurnDirection.FORWARD))
        val (direction, clicks) = move
        assertEquals(TurnDirection.BACKWARD, direction)
        assertEquals(4, clicks)
        assertFalse(move.solved)
    }

    @Test
    fun `turn directions keep their order`() {
        assertEquals(listOf(TurnDirection.FORWARD, TurnDirection.BACKWARD), TurnDirection.entries)
    }

    @Test
    fun `the escape room lock uses interface 79`() {
        val lock = CombinationLockLayout.GENERIC
        assertEquals(79, lock.interfaceId)
        assertEquals(4, lock.dials)
        assertEquals(17, lock.enterComponent)
        assertEquals(listOf(9, 10, 11, 12), (0 until 4).map { lock.arrowComponent(it, TurnDirection.BACKWARD) })
        assertEquals(listOf(13, 14, 15, 16), (0 until 4).map { lock.arrowComponent(it, TurnDirection.FORWARD) })
    }

    @Test
    fun `the seer lock uses interface 298`() {
        val lock = CombinationLockLayout.SEER
        assertEquals(298, lock.interfaceId)
        assertEquals(4, lock.dials)
        assertEquals(21, lock.enterComponent)
        assertEquals(listOf(13, 14, 15, 16), (0 until 4).map { lock.arrowComponent(it, TurnDirection.BACKWARD) })
        assertEquals(listOf(17, 18, 19, 20), (0 until 4).map { lock.arrowComponent(it, TurnDirection.FORWARD) })
    }

    @Test
    fun `a custom layout reports its own components`() {
        val lock = CombinationLockLayout(
            interfaceId = 1234,
            letterComponents = intArrayOf(1, 2, 3),
            backwardArrows = intArrayOf(4, 5, 6),
            forwardArrows = intArrayOf(7, 8, 9),
            enterComponent = 10,
        )
        assertEquals(1234, lock.interfaceId)
        assertEquals(3, lock.dials)
        assertEquals(10, lock.enterComponent)
        assertEquals(5, lock.arrowComponent(1, TurnDirection.BACKWARD))
        assertEquals(9, lock.arrowComponent(2, TurnDirection.FORWARD))
    }

    @Test
    fun `the generic lock facade mirrors the escape room layout`() {
        assertEquals(79, CombinationLockInterface.INTERFACE_ID)
        assertEquals(17, CombinationLockInterface.ENTER_COMPONENT)
        assertEquals(4, CombinationLockInterface.DIALS)
        for (dial in 0 until CombinationLockInterface.DIALS) for (direction in TurnDirection.entries) {
            assertEquals(
                CombinationLockLayout.GENERIC.arrowComponent(dial, direction),
                CombinationLockInterface.arrowComponent(dial, direction),
            )
        }
    }
}
