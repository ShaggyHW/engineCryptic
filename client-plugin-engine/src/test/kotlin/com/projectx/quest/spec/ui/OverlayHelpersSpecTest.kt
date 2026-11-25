package com.projectx.quest.spec.ui

import com.projectx.game.math.Vector2f
import com.projectx.quest.overlay.arrow
import com.projectx.quest.overlay.bearingRad
import com.projectx.quest.overlay.offscreenBearingArrow
import com.projectx.quest.overlay.pulseAlpha
import com.projectx.quest.overlay.stripHtml
import com.projectx.ui.backend.dsl.commands.DrawConvexPolyFilledCommand
import com.projectx.ui.backend.dsl.commands.DrawLineCommand
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OverlayHelpersSpecTest {

    private val red = 0xFFFF0000.toInt()

    private fun near(expected: Float, actual: Float, what: String, tolerance: Float = 0.01f) =
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected but was $actual")

    private fun drawn(block: BackgroundDrawListScope.() -> Unit) = BackgroundDrawListScope().apply(block).drawCommands.toList()

    private fun assertTriangle(expected: FloatArray, poly: DrawConvexPolyFilledCommand) {
        assertEquals(expected.size, poly.points.size, "triangle vertex count")
        expected.forEachIndexed { i, v -> near(v, poly.points[i], "triangle coordinate $i") }
    }

    @Test
    fun `stripHtml turns line breaks of every spelling into newlines`() {
        assertEquals("one\ntwo\nthree\nfour", stripHtml("one<br>two<BR/>three<br />four"))
    }

    @Test
    fun `stripHtml renders list items as dashed lines`() {
        assertEquals("- bucket\n- pot\n", stripHtml("<ul><li>bucket</li><LI>pot</LI></ul>"))
    }

    @Test
    fun `stripHtml drops other tags but keeps their text`() {
        assertEquals("Talk to Hans in the courtyard.", stripHtml("Talk to <b>Hans</b> in the <a href=\"x\">courtyard</a>."))
    }

    @Test
    fun `stripHtml decodes the four common entities after removing tags`() {
        assertEquals("Fish & Chips <b> a b", stripHtml("Fish &amp; Chips &lt;b&gt; a&nbsp;b"))
    }

    @Test
    fun `stripHtml leaves plain text untouched and does not trim`() {
        assertEquals("  plain text  ", stripHtml("  plain text  "))
        assertEquals("", stripHtml(""))
    }

    @Test
    fun `pulseAlpha keeps the colour channels and discards the input alpha`() {
        for (base in listOf(0x00123456, 0xFF123456.toInt(), 0x7F00FFFF, 0)) {
            val out = pulseAlpha(base)
            assertEquals(base and 0x00FFFFFF, out and 0x00FFFFFF, "rgb of ${base.toString(16)}")
        }
    }

    @Test
    fun `pulseAlpha alpha stays inside the default band`() {
        repeat(500) {
            val alpha = pulseAlpha(0x00FFFF) ushr 24
            assertTrue(alpha in 96..255, "alpha $alpha outside 96..255")
        }
    }

    @Test
    fun `pulseAlpha with equal bounds is constant and clamps to a byte`() {
        assertEquals(200, pulseAlpha(0x00FFFF, minAlpha = 200, maxAlpha = 200) ushr 24)
        assertEquals(0, pulseAlpha(0x00FFFF, minAlpha = -40, maxAlpha = -40) ushr 24)
        assertEquals(255, pulseAlpha(0x00FFFF, minAlpha = 400, maxAlpha = 400) ushr 24)
    }

    @Test
    fun `pulseAlpha sweeps from its minimum to its maximum over a period`() {
        val seen = HashSet<Int>()
        val end = System.currentTimeMillis() + 120
        while (System.currentTimeMillis() < end) seen += pulseAlpha(0x00FFFF, periodMs = 40L) ushr 24
        assertTrue(seen.max() >= 250, "never reached near 255: $seen")
        assertTrue(seen.min() <= 101, "never reached near 96: $seen")
    }

    @Test
    fun `bearingRad is the world angle measured from east, counter-clockwise`() {
        near(0f, bearingRad(3200, 3200, 3210, 3200), "east")
        near((PI / 2).toFloat(), bearingRad(3200, 3200, 3200, 3210), "north")
        near(PI.toFloat(), bearingRad(3200, 3200, 3190, 3200), "west")
        near((-PI / 2).toFloat(), bearingRad(3200, 3200, 3200, 3190), "south")
        near((PI / 4).toFloat(), bearingRad(0, 0, 5, 5), "north-east")
        near(0f, bearingRad(7, 7, 7, 7), "same tile")
    }

    @Test
    fun `arrow draws a shaft line and a filled head with the default sizes`() {
        val cmds = drawn { arrow(Vector2f(0f, 0f), Vector2f(100f, 0f), red) }
        assertEquals(2, cmds.size)
        val shaft = assertIs<DrawLineCommand>(cmds[0])
        near(0f, shaft.x1, "shaft x1"); near(0f, shaft.y1, "shaft y1")
        near(90.4f, shaft.x2, "shaft x2"); near(0f, shaft.y2, "shaft y2")
        near(3f, shaft.thickness, "thickness")
        assertEquals(red, shaft.color)
        val head = assertIs<DrawConvexPolyFilledCommand>(cmds[1])
        assertEquals(red, head.color)
        assertTriangle(floatArrayOf(100f, 0f, 84f, 8f, 84f, -8f), head)
    }

    @Test
    fun `arrow head geometry follows the direction and the requested sizes`() {
        val cmds = drawn { arrow(Vector2f(10f, 10f), Vector2f(40f, 50f), red, thickness = 5f, headSize = 20f) }
        val shaft = assertIs<DrawLineCommand>(cmds[0])
        near(10f, shaft.x1, "x1"); near(10f, shaft.y1, "y1")
        near(32.8f, shaft.x2, "x2"); near(40.4f, shaft.y2, "y2")
        near(5f, shaft.thickness, "thickness")
        assertTriangle(floatArrayOf(40f, 50f, 20f, 40f, 36f, 28f), assertIs(cmds[1]))
    }

    @Test
    fun `arrow between coincident points draws nothing`() {
        assertTrue(drawn { arrow(Vector2f(5f, 5f), Vector2f(5f, 5f), red) }.isEmpty())
    }

    @Test
    fun `offscreen arrow pins to the right margin for an eastward bearing`() {
        val cmds = drawn { offscreenBearingArrow(Vector2f(400f, 300f), 800f, 600f, 0f, red) }
        assertEquals(2, cmds.size)
        val shaft = assertIs<DrawLineCommand>(cmds[0])
        near(708f, shaft.x1, "tail x"); near(300f, shaft.y1, "tail y")
        near(729.2f, shaft.x2, "shaft end x")
        near(4f, shaft.thickness, "thickness")
        assertTriangle(floatArrayOf(740f, 300f, 722f, 309f, 722f, 291f), assertIs(cmds[1]))
    }

    @Test
    fun `offscreen arrow points up the screen for a northward bearing`() {
        val cmds = drawn { offscreenBearingArrow(Vector2f(400f, 300f), 800f, 600f, (PI / 2).toFloat(), red) }
        val shaft = assertIs<DrawLineCommand>(cmds[0])
        near(400f, shaft.x1, "tail x"); near(92f, shaft.y1, "tail y")
        val head = assertIs<DrawConvexPolyFilledCommand>(cmds[1])
        near(400f, head.points[0], "tip x"); near(60f, head.points[1], "tip y")
    }

    @Test
    fun `offscreen arrow stops at the first margin it meets on a diagonal`() {
        val cmds = drawn { offscreenBearingArrow(Vector2f(400f, 300f), 800f, 600f, (PI / 4).toFloat(), red) }
        val head = assertIs<DrawConvexPolyFilledCommand>(cmds[1])
        near(640f, head.points[0], "tip x", 0.1f); near(60f, head.points[1], "tip y", 0.1f)
    }

    @Test
    fun `offscreen arrow honours custom margin and length`() {
        val cmds = drawn { offscreenBearingArrow(Vector2f(100f, 100f), 200f, 200f, 0f, red, margin = 0f, arrowLen = 50f) }
        val shaft = assertIs<DrawLineCommand>(cmds[0])
        near(150f, shaft.x1, "tail x")
        val head = assertIs<DrawConvexPolyFilledCommand>(cmds[1])
        near(200f, head.points[0], "tip x"); near(100f, head.points[1], "tip y")
    }

    @Test
    fun `offscreen arrow draws nothing when the centre is already past the margin it heads for`() {
        assertTrue(drawn { offscreenBearingArrow(Vector2f(790f, 300f), 800f, 600f, 0f, red) }.isEmpty())
    }
}
