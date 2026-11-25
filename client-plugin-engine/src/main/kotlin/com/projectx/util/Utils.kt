package com.projectx.util

import com.projectx.profiling.PlayerProfiles
import world.gregs.voidps.type.Tile
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

private val entropy = SecureRandom()

fun random(max: Int): Int = entropy.nextInt(max)

fun random(min: Int, max: Int): Int = entropy.nextInt(min, max)

fun random(max: Long): Long = entropy.nextLong(max)

fun random(min: Long, max: Long): Long = entropy.nextLong(min, max)

fun randomInclusive(max: Int): Int = entropy.nextInt(max + 1)

fun randomInclusive(min: Int, max: Int): Int = entropy.nextInt(min, max + 1)

fun randomD(max: Double): Double = entropy.nextDouble() * max

fun randomD(min: Double, max: Double): Double = min + (max - min) * entropy.nextDouble()

fun randomD(): Double = entropy.nextDouble()

private fun normalSpread(variance: Double, scale: Double): Double = entropy.nextGaussian() * sqrt(variance * scale)

private fun profileScale(): Double = PlayerProfiles.get().gaussVariance

fun gaussian(mean: Int, variance: Int): Int = (mean + normalSpread(variance.toDouble(), profileScale())).toInt()

fun gaussian(mean: Long, variance: Long): Long = (mean + normalSpread(variance.toDouble(), profileScale())).toLong()

fun nonProfiledGaussian(mean: Int, variance: Int): Int = (mean + normalSpread(variance.toDouble(), 1.0)).toInt()

val ANGLE_DIRECTION_DELTA: Array<Pair<Int, Int>> = arrayOf(
    0 to -1,
    -1 to -1,
    -1 to 0,
    -1 to 1,
    0 to 1,
    1 to 1,
    1 to 0,
    1 to -1,
)

private const val FULL_TURN = 16384
private const val ANGLE_UNITS_PER_RADIAN = FULL_TURN / (2 * PI)

fun getDirDelta(angle: Int): Pair<Int, Int>? = ANGLE_DIRECTION_DELTA.getOrNull(angle shr 11)

fun getAngleTo(from: Tile, to: Tile): Int = getAngleTo(to.x - from.x, to.y - from.y)

fun getAngleTo(xOffset: Int, yOffset: Int): Int {
    val radians = atan2(-xOffset.toDouble(), -yOffset.toDouble())
    return (radians * ANGLE_UNITS_PER_RADIAN).toInt() and (FULL_TURN - 1)
}

fun hashFromInterface(interfaceId: Int, componentId: Int): Int = (interfaceId shl 16) + componentId

fun interfaceIdFromHash(hash: Int): Int = hash shr 16

fun componentIdFromHash(hash: Int): Int = hash and 0xFFFF

fun millisElapsed(start: Long): Long = System.currentTimeMillis() - start

fun secondsElapsed(start: Long): Float = millisElapsed(start) / 1000f

fun minutesElapsed(start: Long): Float = secondsElapsed(start) / 60f

fun hoursElapsed(start: Long): Float = minutesElapsed(start) / 60f

fun getXpPerHour(startingXp: Int, currentXp: Int, startTime: Long): Int =
    ((currentXp - startingXp) / hoursElapsed(startTime)).toInt()

fun getFormattedXpPerHour(startingXp: Int, currentXp: Int, startTime: Long): String =
    format(getXpPerHour(startingXp, currentXp, startTime))

fun getUnitsPerHour(unitsGained: Int, startTime: Long): Int = (unitsGained / hoursElapsed(startTime)).toInt()

fun getFormattedUnitsPerHour(unitsGained: Int, startTime: Long): String = format(getUnitsPerHour(unitsGained, startTime))

fun format(number: Int): String = "%,d".format(Locale.US, number)

@JvmOverloads
fun format(number: Float, decimals: Int = 2): String = fixedPoint(number, decimals)

@JvmOverloads
fun format(number: Double, decimals: Int = 2): String = fixedPoint(number, decimals)

private fun fixedPoint(number: Any, decimals: Int): String = "%.${decimals}f".format(Locale.US, number)

fun formatElapsedTime(currTime: Long, startTime: Long): String {
    val totalSeconds = (currTime - startTime) / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

fun Byte.toBitString(): String = nibbles(toInt() and 0xFF, Byte.SIZE_BITS)

fun Int.toBitString(): String = nibbles(this, Int.SIZE_BITS)

private fun nibbles(bits: Int, width: Int): String = buildString(width + width / 4) {
    for (position in width - 1 downTo 0) {
        append(if ((bits ushr position) and 1 == 1) '1' else '0')
        if (position != 0 && position % 4 == 0) append(' ')
    }
}

private const val MAX_TABLE_LEVEL = 120

private fun experienceThreshold(level: Int): Int {
    var points = 0L
    for (step in 1 until level) points += floor(step + 300.0 * 2.0.pow(step / 7.0)).toLong()
    return (points / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private val experienceTable = IntArray(MAX_TABLE_LEVEL + 1) { if (it < 1) 0 else experienceThreshold(it) }

fun getXpForLevel(level: Int): Int = when {
    level <= 1 -> 0
    level <= MAX_TABLE_LEVEL -> experienceTable[level]
    else -> experienceThreshold(level)
}

fun getLevelForXp(xp: Int): Int {
    var level = MAX_TABLE_LEVEL
    while (level > 1 && experienceTable[level] > xp) level--
    return level
}
