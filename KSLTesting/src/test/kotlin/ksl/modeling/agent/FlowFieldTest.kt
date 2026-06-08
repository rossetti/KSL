/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.modeling.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Phase 5.1 regression tests for [FlowField]: distance-from-sources
 *  field over a [GridGraph] with point-level conversions for
 *  continuous-space agents.
 */
class FlowFieldTest {

    // ── Construction & basic queries ───────────────────────────────────────

    @Test
    fun flowFieldDistancesMatchDistanceField() {
        val g = GridGraph(5, 5, movementRule = MovementRule.MOORE)
        val sources = setOf(Cell(0, 0))
        val field = FlowField(g, sources)

        // distances should be exactly the underlying field.
        val raw = g.distanceField(sources)
        assertEquals(raw, field.distances)
        // Source cell has distance 0.
        assertEquals(0.0, field.distances[Cell(0, 0)])
        // Diagonally far corner has positive distance.
        assertTrue((field.distances[Cell(4, 4)] ?: 0.0) > 0.0)
    }

    @Test
    fun flowFieldArrivedAtIsTrueAtSourcesFalseElsewhere() {
        val g = GridGraph(10, 10)
        val sources = setOf(Cell(0, 0), Cell(9, 9))
        val field = FlowField(g, sources)

        assertTrue(field.arrivedAt(field.centerOf(Cell(0, 0))))
        assertTrue(field.arrivedAt(field.centerOf(Cell(9, 9))))
        assertFalse(field.arrivedAt(field.centerOf(Cell(5, 5))))
        // Out-of-bounds is not arrived (defensive — invalid placement).
        assertFalse(field.arrivedAt(Point2D(-5.0, -5.0)))
    }

    // ── directionAt routing ─────────────────────────────────────────────────

    @Test
    fun flowFieldDirectionAtPointsTowardNearestSourceNoObstacles() {
        // Single source at (0, 0). From any cell, the unit direction
        // should have non-positive x and non-positive y (i.e., pointing
        // toward the lower-left source).
        val g = GridGraph(10, 10, movementRule = MovementRule.MOORE)
        val field = FlowField(g, setOf(Cell(0, 0)))

        for (col in 1..9) for (row in 1..9) {
            val p = field.centerOf(Cell(col, row))
            val dir = field.directionAt(p)
            assertTrue(
                dir.x <= 1e-9 && dir.y <= 1e-9,
                "at $col,$row expected direction toward (0,0); got $dir",
            )
            // Magnitude is 1 (unit vector) — we're far from the source.
            assertEquals(1.0, dir.magnitude, 1e-9, "non-unit direction at $col,$row")
        }
    }

    @Test
    fun flowFieldDirectionAtRoutesAroundBlockedCells() {
        // 7x3 grid with a wall in column 3 (rows 0 and 1 blocked, row 2
        // passable). Source at the rightmost column. From a point on
        // the left of the wall but rows 0 or 1, the gradient must
        // route up to row 2 first.
        val g = GridGraph(7, 3, movementRule = MovementRule.MOORE)
        g.block(Cell(3, 0))
        g.block(Cell(3, 1))
        val field = FlowField(g, setOf(Cell(6, 0)))

        // From cell (2, 0): the *direct* east move is blocked. The
        // Moore-grid optimal step goes northeast (toward row 2), so
        // direction should have positive y component.
        val p = field.centerOf(Cell(2, 0))
        val dir = field.directionAt(p)
        assertTrue(
            dir.y > 0.0,
            "expected to route up around the wall; direction was $dir",
        )
    }

    @Test
    fun flowFieldDirectionAtWithMultipleSources() {
        // Two sources at opposite corners. A point near the upper-left
        // should head to Cell(0,9); a point near the lower-right should
        // head to Cell(9,0).
        val g = GridGraph(10, 10, movementRule = MovementRule.MOORE)
        val field = FlowField(g, setOf(Cell(0, 9), Cell(9, 0)))

        val ulPoint = field.centerOf(Cell(2, 7))
        val ulDir = field.directionAt(ulPoint)
        // Should head toward (0, 9): negative x, positive y.
        assertTrue(ulDir.x <= 0.0, "upper-left point should head west; got $ulDir")
        assertTrue(ulDir.y >= 0.0, "upper-left point should head north; got $ulDir")

        val lrPoint = field.centerOf(Cell(7, 2))
        val lrDir = field.directionAt(lrPoint)
        // Should head toward (9, 0): positive x, negative y.
        assertTrue(lrDir.x >= 0.0, "lower-right point should head east; got $lrDir")
        assertTrue(lrDir.y <= 0.0, "lower-right point should head south; got $lrDir")
    }

    @Test
    fun flowFieldDirectionAtSourceReturnsZero() {
        val g = GridGraph(5, 5)
        val field = FlowField(g, setOf(Cell(2, 2)))
        // Already at the source — direction should be zero.
        val dir = field.directionAt(field.centerOf(Cell(2, 2)))
        assertEquals(Point2D.ORIGIN, dir)
    }

    // ── Edge cases ─────────────────────────────────────────────────────────

    @Test
    fun flowFieldDistanceAtUnreachable() {
        // 5x5 grid with a wall separating left and right halves except
        // top row; source on right side, query on bottom-left, where
        // there's no path through the wall.
        val g = GridGraph(5, 5, movementRule = MovementRule.VON_NEUMANN)
        // Wall at column 2 rows 0..4 (full block), entirely sealing
        // off the left half.
        for (r in 0 until 5) g.block(Cell(2, r))
        val field = FlowField(g, setOf(Cell(4, 2)))

        // Cell on the left side is unreachable.
        val unreachable = field.distanceAt(field.centerOf(Cell(0, 0)))
        assertEquals(Double.POSITIVE_INFINITY, unreachable)
        // Cell on the right side is reachable.
        val reachable = field.distanceAt(field.centerOf(Cell(4, 4)))
        assertTrue(reachable.isFinite())
    }

    @Test
    fun flowFieldOutOfBoundsPointBehavesDefensively() {
        val g = GridGraph(5, 5)
        val field = FlowField(g, setOf(Cell(0, 0)))

        val outside = Point2D(-10.0, -10.0)
        // Distance is infinity, direction is zero, not crashed.
        assertEquals(Double.POSITIVE_INFINITY, field.distanceAt(outside))
        assertEquals(Point2D.ORIGIN, field.directionAt(outside))
        assertFalse(field.arrivedAt(outside))
    }

    @Test
    fun flowFieldNonUnitCellSizeAndOrigin() {
        // cellSize = 2.0, origin = (10, 10). Cell (0,0) covers
        // [10, 12) x [10, 12). Center is at (11, 11).
        val g = GridGraph(5, 5)
        val field = FlowField(
            graph = g,
            sources = setOf(Cell(0, 0)),
            cellSize = 2.0,
            origin = Point2D(10.0, 10.0),
        )

        // centerOf and cellOf round-trip within bounds.
        assertEquals(Point2D(11.0, 11.0), field.centerOf(Cell(0, 0)))
        assertEquals(Cell(0, 0), field.cellOf(Point2D(11.0, 11.0)))
        // Off-center points still map to the right cell.
        assertEquals(Cell(0, 0), field.cellOf(Point2D(10.5, 11.5)))
        assertEquals(Cell(1, 0), field.cellOf(Point2D(12.5, 11.0)))
        // Points before origin map to negative-index cells (out of bounds).
        assertEquals(Cell(-1, -1), field.cellOf(Point2D(9.0, 9.0)))
        assertEquals(Double.POSITIVE_INFINITY, field.distanceAt(Point2D(9.0, 9.0)))

        // arrivedAt works in the shifted coord system.
        assertTrue(field.arrivedAt(Point2D(11.0, 11.0)))
        assertFalse(field.arrivedAt(Point2D(15.0, 15.0)))
        // And direction at a distant point still points roughly back toward
        // the source center.
        val dir = field.directionAt(Point2D(18.0, 18.0))
        assertNotEquals(Point2D.ORIGIN, dir)
        assertTrue(dir.x < 0.0 && dir.y < 0.0, "expected southwest direction; got $dir")
    }
}
