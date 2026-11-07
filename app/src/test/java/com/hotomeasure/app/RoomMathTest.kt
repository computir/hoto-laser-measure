package com.hotomeasure.app

import com.hotomeasure.app.data.Corner
import com.hotomeasure.app.data.Corner.INSIDE
import com.hotomeasure.app.data.Corner.OUTSIDE
import com.hotomeasure.app.data.RoomMath
import com.hotomeasure.app.data.RoomResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMathTest {

    private fun closed(walls: List<Int>, corners: List<Corner> = List(walls.size) { INSIDE }) =
        RoomMath.solve(walls, corners) as RoomResult.Closed

    @Test
    fun rectangleMeasuredAllTheWayRound() {
        val r = closed(listOf(4000, 3000, 4000, 3000))
        assertEquals(12e6, r.areaMm2, 1.0)
        assertEquals(14000.0, r.perimeterMm, 0.1)
        assertFalse(r.calculatedLast)
        assertFalse(r.cornerMismatch)
    }

    @Test
    fun rectangleFromThreeWallsCalculatesTheFourth() {
        val r = closed(listOf(4000, 3000, 4000))
        assertTrue(r.calculatedLast)
        assertEquals(listOf(4000, 3000, 4000, 3000), r.walls)
        assertEquals(12e6, r.areaMm2, 1.0)
        assertFalse(r.cornerMismatch)
    }

    @Test
    fun rectangleFromTwoWalls() {
        val r = closed(listOf(4000, 3000))
        assertEquals(12e6, r.areaMm2, 1.0)
        assertEquals(14000.0, r.perimeterMm, 0.1)
    }

    // L-shaped room: 6 x 3 m with a 3 x 2 m wing. One corner sticks out into the room.
    private val lWalls = listOf(6000, 3000, 3000, 2000, 3000, 5000)
    private val lCorners = listOf(INSIDE, INSIDE, OUTSIDE, INSIDE, INSIDE, INSIDE)

    @Test
    fun lShapedRoom() {
        val r = closed(lWalls, lCorners)
        assertEquals(24e6, r.areaMm2, 1.0)
        assertEquals(22000.0, r.perimeterMm, 0.1)
        assertFalse(r.cornerMismatch)
    }

    @Test
    fun lShapedRoomWithTheLastWallCalculated() {
        val r = closed(lWalls.dropLast(1), lCorners.dropLast(1))
        assertTrue(r.calculatedLast)
        assertEquals(5000, r.walls.last())
        assertEquals(24e6, r.areaMm2, 1.0)
        assertFalse(r.cornerMismatch)
    }

    @Test
    fun smallMeasuringErrorsAreSpreadOut() {
        val r = closed(listOf(4010, 2995, 3990, 3005))
        assertEquals(12e6, r.areaMm2, 12e6 * 0.005)
        assertTrue(r.correctionMm in 1.0..15.0)
    }

    @Test
    fun wrongShapeDoesNotClose() {
        val r = RoomMath.solve(listOf(4000, 3000, 4000, 1000), List(4) { INSIDE })
        assertTrue(r is RoomResult.Open)
        assertEquals(2000.0, (r as RoomResult.Open).gapMm, 1.0)
    }

    @Test
    fun cornersThatDontAddUpAreFlagged() {
        // Lengths of a rectangle, but one corner wrongly marked as outside.
        val r = RoomMath.solve(listOf(4000, 3000, 4000, 3000), listOf(INSIDE, OUTSIDE, INSIDE, INSIDE))
        assertTrue(r is RoomResult.Open || (r as RoomResult.Closed).cornerMismatch)
    }

    @Test
    fun cornersRoundTripThroughStorage() {
        val cs = listOf(INSIDE, OUTSIDE, INSIDE)
        assertEquals("IOI", Corner.encode(cs))
        assertEquals(cs + INSIDE, Corner.decode("IOI", 4))
    }
}
