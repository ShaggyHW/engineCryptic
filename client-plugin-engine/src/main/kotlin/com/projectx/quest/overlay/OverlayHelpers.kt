package com.projectx.quest.overlay

import com.projectx.game.math.Vector2f
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val RGB_BITS = 0x00FFFFFF
private const val SHAFT_SHARE_OF_HEAD = 0.6f
private const val NEGLIGIBLE = 0.001f
private const val EDGE_ARROW_THICKNESS = 4f
private const val EDGE_ARROW_HEAD = 18f

/** [baseColor] with its alpha swapped for one that breathes between [minAlpha] and [maxAlpha] every [periodMs]. */
fun pulseAlpha(
    baseColor: Int,
    periodMs: Long = 900L, minAlpha: Int = 96, maxAlpha: Int = 255,
): Int {
    val cycle = (System.currentTimeMillis() % periodMs).toDouble() / periodMs
    val swing = 0.5 + 0.5 * sin(cycle * 2.0 * PI)
    val alpha = (minAlpha + (maxAlpha - minAlpha) * swing).toInt().coerceIn(0, 255)
    return (baseColor and RGB_BITS) or (alpha shl 24)
}

/** A shaft ending just inside a filled head whose tip sits exactly on [to]. */
fun BackgroundDrawListScope.arrow(from: Vector2f, to: Vector2f, color: Int, thickness: Float = 3f, headSize: Float = 16f) {
    val run = to.x - from.x
    val rise = to.y - from.y
    val length = hypot(run, rise)
    if (length < NEGLIGIBLE) return
    val ux = run / length
    val uy = rise / length
    val inset = headSize * SHAFT_SHARE_OF_HEAD
    line(from, Vector2f(to.x - ux * inset, to.y - uy * inset), color, thickness)
    val backX = to.x - ux * headSize
    val backY = to.y - uy * headSize
    val wing = headSize / 2f
    val head = floatArrayOf(
        to.x, to.y,
        backX - uy * wing, backY + ux * wing,
        backX + uy * wing, backY - ux * wing,
    )
    convexPolyFilled(head, color)
}

/** Points from the edge of the inset screen box toward a world bearing whose target is off screen. */
fun BackgroundDrawListScope.offscreenBearingArrow(center: Vector2f, screenW: Float, screenH: Float, bearingRad: Float,
                                                   color: Int, margin: Float = 60f, arrowLen: Float = 32f) {
    val dirX = cos(bearingRad)
    val dirY = -sin(bearingRad)
    val left = margin
    val right = screenW - margin
    val top = margin
    val bottom = screenH - margin
    val travel = buildList {
        if (abs(dirX) > NEGLIGIBLE) {
            add((left - center.x) / dirX)
            add((right - center.x) / dirX)
        }
        if (abs(dirY) > NEGLIGIBLE) {
            add((top - center.y) / dirY)
            add((bottom - center.y) / dirY)
        }
    }.filter { it > 0f }.minOrNull() ?: return
    val tip = Vector2f(
        (center.x + dirX * travel).coerceIn(left, right),
        (center.y + dirY * travel).coerceIn(top, bottom),
    )
    val tail = Vector2f(tip.x - dirX * arrowLen, tip.y - dirY * arrowLen)
    arrow(tail, tip, color, EDGE_ARROW_THICKNESS, EDGE_ARROW_HEAD)
}

/** World angle from one tile to another, counter-clockwise from east, in radians. */
fun bearingRad(fromX: Int, fromY: Int, toX: Int, toY: Int): Float {
    val north = (toY - fromY).toDouble()
    val east = (toX - fromX).toDouble()
    return atan2(north, east).toFloat()
}

private val lineBreakTag = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val listItemEnd = Regex("</li>", RegexOption.IGNORE_CASE)
private val listItemStart = Regex("<li>", RegexOption.IGNORE_CASE)
private val anyTag = Regex("<[^>]*>")
private val entities = listOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">")

/** Guide HTML as panel text: breaks and list items become lines, other markup goes, four entities decode. */
fun stripHtml(s: String): String {
    val plain = s.replace(lineBreakTag, "\n")
        .replace(listItemEnd, "\n")
        .replace(listItemStart, "- ")
        .replace(anyTag, "")
    return entities.fold(plain) { text, (entity, glyph) -> text.replace(entity, glyph) }
}
