package com.hotomeasure.app.data

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The corner at the end of a wall, seen from inside the room. A plain box room has four
 * INSIDE corners. An OUTSIDE corner sticks out into the room, e.g. around a chimney breast or
 * where an L-shaped room turns. Every closed room with square corners has exactly
 * four more inside corners than outside ones.
 */
enum class Corner(val code: Char) {
    INSIDE('I'), OUTSIDE('O');

    fun toggled() = if (this == INSIDE) OUTSIDE else INSIDE

    companion object {
        fun of(c: Char) = if (c == 'O') OUTSIDE else INSIDE
        fun encode(list: List<Corner>) = list.joinToString("") { it.code.toString() }
        fun decode(s: String, n: Int) = List(n) { i -> s.getOrNull(i)?.let(::of) ?: INSIDE }
    }
}

/** A point on the floor plan, in millimetres. */
data class Pt(val x: Double, val y: Double)

sealed interface RoomResult {
    /** The room closes. [walls] includes a calculated last wall when [calculatedLast]. */
    data class Closed(
        val walls: List<Int>,
        val corners: List<Corner>,
        val outline: List<Pt>,
        val areaMm2: Double,
        val perimeterMm: Double,
        val calculatedLast: Boolean,
        /** The largest change made to any wall to make the outline meet, in mm. */
        val correctionMm: Double,
        /** Corner types don't add up to a closed room (inside - outside should be 4). */
        val cornerMismatch: Boolean,
    ) : RoomResult

    /** The walls don't meet; [gapMm] is how far the end is from the start. */
    data class Open(val outline: List<Pt>, val gapMm: Double) : RoomResult
}

/**
 * Works out a room's shape from wall lengths measured in order around it, with every corner
 * at 90 degrees. Walks the walls counter-clockwise: inside corners turn left, outside corners
 * turn right.
 */
object RoomMath {

    /** The open outline so far, for drawing while walls are still being measured. */
    fun trace(walls: List<Int>, corners: List<Corner>): List<Pt> {
        var x = 0.0; var y = 0.0
        var dx = 1; var dy = 0
        val pts = mutableListOf(Pt(0.0, 0.0))
        walls.forEachIndexed { i, len ->
            x += len * dx; y += len * dy
            pts += Pt(x, y)
            val turned = turn(dx, dy, corners.getOrElse(i) { Corner.INSIDE })
            dx = turned.first; dy = turned.second
        }
        return pts
    }

    /**
     * Closes the room if possible. Small gaps (from aiming and holding the measure) are spread
     * over the walls in proportion to their length. If the end is short of the start along the
     * direction of the next wall, that last wall is calculated instead of measured.
     */
    fun solve(walls: List<Int>, corners: List<Corner>): RoomResult {
        if (walls.size < 2) return RoomResult.Open(trace(walls, corners), Double.NaN)
        val cs = Corner.decode(Corner.encode(corners), walls.size).toMutableList()
        val perimeter = walls.sumOf { it.toDouble() }

        // Two walls with an inside corner between them: a rectangle.
        if (walls.size == 2 && cs[0] == Corner.INSIDE) {
            val all = listOf(walls[0], walls[1], walls[0], walls[1])
            return closed(all, List(4) { Corner.INSIDE }, calculated = true)
        }

        val pts = trace(walls, cs)
        val end = pts.last()
        val gapX = -end.x; val gapY = -end.y
        val tol = tolerance(perimeter)

        if (hypot(gapX, gapY) <= tol) return closed(walls, cs, calculated = false)

        // Direction of the next wall, after the corner at the end of the last measured wall.
        val (hx, hy) = heading(cs, walls.size)
        val along = gapX * hx + gapY * hy
        val across = abs(gapX * -hy + gapY * hx)
        if (along > tol && across <= tol) {
            val all = walls + along.toInt()
            // The new wall's end corner meets the first wall; pick what makes the corners add up.
            val partial = cs.take(walls.size)
            val net = partial.count { it == Corner.INSIDE } - partial.count { it == Corner.OUTSIDE }
            val last = if (net + 1 == 4) Corner.INSIDE else Corner.OUTSIDE
            return closed(all, partial + last, calculated = true)
        }
        return RoomResult.Open(pts, hypot(gapX, gapY))
    }

    /** How far apart the ends may be and still count as the same corner. */
    fun tolerance(perimeterMm: Double) = max(50.0, perimeterMm * 0.015)

    private fun closed(walls: List<Int>, corners: List<Corner>, calculated: Boolean): RoomResult.Closed {
        // Each wall's direction along the walk.
        val dirs = mutableListOf<Pair<Int, Int>>()
        var dx = 1; var dy = 0
        walls.indices.forEach { i ->
            dirs += dx to dy
            val t = turn(dx, dy, corners[i]); dx = t.first; dy = t.second
        }
        // Spread the remaining error along x over the x-walls, and along y over the y-walls.
        val ex = walls.indices.sumOf { walls[it] * dirs[it].first.toDouble() }
        val ey = walls.indices.sumOf { walls[it] * dirs[it].second.toDouble() }
        val lx = walls.indices.filter { dirs[it].first != 0 }.sumOf { walls[it].toDouble() }
        val ly = walls.indices.filter { dirs[it].second != 0 }.sumOf { walls[it].toDouble() }
        val adjusted = walls.indices.map { i ->
            val (ddx, ddy) = dirs[i]
            val len = walls[i].toDouble()
            when {
                ddx != 0 && lx > 0 -> len - ex * ddx * len / lx
                ddy != 0 && ly > 0 -> len - ey * ddy * len / ly
                else -> len
            }
        }
        var x = 0.0; var y = 0.0
        val outline = mutableListOf(Pt(0.0, 0.0))
        adjusted.forEachIndexed { i, len ->
            x += len * dirs[i].first; y += len * dirs[i].second
            if (i < adjusted.lastIndex) outline += Pt(x, y)
        }
        var twice = 0.0
        for (i in outline.indices) {
            val a = outline[i]; val b = outline[(i + 1) % outline.size]
            twice += a.x * b.y - b.x * a.y
        }
        val net = corners.count { it == Corner.INSIDE } - corners.count { it == Corner.OUTSIDE }
        return RoomResult.Closed(
            walls = walls,
            corners = corners,
            outline = outline,
            areaMm2 = abs(twice) / 2,
            perimeterMm = walls.sumOf { it.toDouble() },
            calculatedLast = calculated,
            correctionMm = walls.indices.maxOfOrNull { abs(adjusted[it] - walls[it]) } ?: 0.0,
            cornerMismatch = net != 4,
        )
    }

    private fun heading(corners: List<Corner>, afterWalls: Int): Pair<Int, Int> {
        var dx = 1; var dy = 0
        repeat(afterWalls) { i -> val t = turn(dx, dy, corners[i]); dx = t.first; dy = t.second }
        return dx to dy
    }

    // Counter-clockwise walk: inside corner = left turn, outside corner = right turn.
    private fun turn(dx: Int, dy: Int, c: Corner) = if (c == Corner.INSIDE) -dy to dx else dy to -dx
}
