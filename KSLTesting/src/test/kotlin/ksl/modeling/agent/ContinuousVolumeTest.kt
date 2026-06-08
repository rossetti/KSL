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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Phase 6.2 tests for [ContinuousVolume] — the 3D analog of
 *  [ContinuousProjection]. Verifies placement, distance / delta
 *  (including torus wrap on all three axes), within / nearest
 *  spatial-hash queries, parity with naive linear scan on a
 *  population, and diagnostic accessors.
 */
class ContinuousVolumeTest {

    // ── Test fixture ───────────────────────────────────────────────────────

    private class VolModel(
        parent: ModelElement,
        torus: Boolean = false,
    ) : AgentModel(parent, "vol") {
        val ctx: Context<Agent> = Context("agents")
        val space: ContinuousVolume<Agent> = ContinuousVolume(
            context = ctx,
            xRange = 0.0..100.0,
            yRange = 0.0..100.0,
            zRange = 0.0..50.0,
            torus = torus,
        )
    }

    // ── Construction / validation ──────────────────────────────────────────

    @Test
    fun constructorRejectsNonPositiveCellSize() {
        val m = Model("ctor-cell")
        val tm = object : AgentModel(m, "vol") {}
        val ctx = tm.Context<AgentModel.Agent>("agents")
        assertThrows<IllegalArgumentException> {
            ContinuousVolume(ctx, 0.0..10.0, 0.0..10.0, 0.0..10.0, cellSize = 0.0)
        }
        assertThrows<IllegalArgumentException> {
            ContinuousVolume(ctx, 0.0..10.0, 0.0..10.0, 0.0..10.0, cellSize = -1.0)
        }
    }

    @Test
    fun defaultCellSizeIsSmallestRangeDividedByDivisor() {
        val m = Model("ctor-default")
        val tm = object : AgentModel(m, "vol") {}
        val ctx = tm.Context<AgentModel.Agent>("agents")
        // min(100, 100, 50) = 50; default divisor = 10 → 5.0
        val space = ContinuousVolume(ctx, 0.0..100.0, 0.0..100.0, 0.0..50.0)
        assertEquals(5.0, space.cellSize, 1e-9)
    }

    // ── Placement / movement / position lookup ─────────────────────────────

    @Test
    fun placeAtAndPositionOf() {
        val m = Model("place")
        val tm = VolModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a)
        assertEquals(null, tm.space.positionOf(a))   // unplaced
        tm.space.placeAt(a, 10.0, 20.0, 5.0)
        assertEquals(Point3D(10.0, 20.0, 5.0), tm.space.positionOf(a))
        // Re-placing updates.
        tm.space.placeAt(a, Point3D(50.0, 60.0, 25.0))
        assertEquals(Point3D(50.0, 60.0, 25.0), tm.space.positionOf(a))
    }

    @Test
    fun moveToIsEquivalentToPlaceAt() {
        val m = Model("move")
        val tm = VolModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a)
        tm.space.placeAt(a, Point3D(0.0, 0.0, 0.0))
        tm.space.moveTo(a, 5.0, 10.0, 15.0)
        assertEquals(Point3D(5.0, 10.0, 15.0), tm.space.positionOf(a))
    }

    @Test
    fun onAgentLeftRemovesPositionAndBucket() {
        val m = Model("left")
        val tm = VolModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, 5.0, 5.0, 5.0)
        assertEquals(1, tm.space.size)
        assertEquals(1, tm.space.occupiedBucketCount)
        tm.ctx.remove(a)
        assertEquals(null, tm.space.positionOf(a))
        assertEquals(0, tm.space.size)
        assertEquals(0, tm.space.occupiedBucketCount)
    }

    // ── Distance / delta ───────────────────────────────────────────────────

    @Test
    fun distanceIsEuclideanForNonTorus() {
        val m = Model("dist-flat")
        val tm = VolModel(m)
        val a = tm.Agent("a"); val b = tm.Agent("b")
        tm.ctx.add(a); tm.ctx.add(b)
        tm.space.placeAt(a, Point3D(0.0, 0.0, 0.0))
        tm.space.placeAt(b, Point3D(3.0, 4.0, 12.0))   // 13 by Pythagoras
        assertEquals(13.0, tm.space.distance(a, b), 1e-9)
        assertEquals(13.0, tm.space.distance(b, a), 1e-9)
    }

    @Test
    fun distanceReturnsNaNForUnplaced() {
        val m = Model("dist-nan")
        val tm = VolModel(m)
        val a = tm.Agent("a"); val b = tm.Agent("b")
        tm.ctx.add(a); tm.ctx.add(b)
        tm.space.placeAt(a, 0.0, 0.0, 0.0)
        // b not placed
        assertTrue(tm.space.distance(a, b).isNaN())
    }

    @Test
    fun distanceWrapsOnAllThreeAxesForTorus() {
        val m = Model("dist-torus")
        val tm = VolModel(m, torus = true)
        // x wraps at 100, y wraps at 100, z wraps at 50.
        // (99, 50, 1) to (1, 50, 49) — short ways are (2, 0, 2).
        val p1 = Point3D(99.0, 50.0, 1.0)
        val p2 = Point3D(1.0, 50.0, 49.0)
        // Expected: sqrt(2² + 0² + 2²) = sqrt(8) = 2 sqrt(2)
        assertEquals(2.0 * sqrt(2.0), tm.space.distance(p1, p2), 1e-9)
    }

    @Test
    fun deltaIsTrivialForNonTorus() {
        val m = Model("delta-flat")
        val tm = VolModel(m)
        val d = tm.space.delta(Point3D(10.0, 20.0, 5.0), Point3D(30.0, 25.0, 15.0))
        assertEquals(20.0, d.x, 1e-9)
        assertEquals(5.0, d.y, 1e-9)
        assertEquals(10.0, d.z, 1e-9)
    }

    @Test
    fun deltaWrapsShortDirectionOnTorus() {
        val m = Model("delta-torus")
        val tm = VolModel(m, torus = true)
        // From (99, 50, 1) → (1, 50, 49): short ways are +2 in x and -2 in z.
        val d = tm.space.delta(Point3D(99.0, 50.0, 1.0), Point3D(1.0, 50.0, 49.0))
        assertEquals(2.0, d.x, 1e-9)
        assertEquals(0.0, d.y, 1e-9)
        assertEquals(-2.0, d.z, 1e-9)
    }

    // ── within / neighborsOf ───────────────────────────────────────────────

    @Test
    fun withinRejectsNegativeRadius() {
        val m = Model("within-neg")
        val tm = VolModel(m)
        assertThrows<IllegalArgumentException> {
            tm.space.within(Point3D(0.0, 0.0, 0.0), -1.0)
        }
    }

    @Test
    fun withinReturnsAgentsOrderedByDistance() {
        val m = Model("within")
        val tm = VolModel(m)
        val a = tm.Agent("a"); val b = tm.Agent("b"); val c = tm.Agent("c")
        tm.ctx.add(a); tm.ctx.add(b); tm.ctx.add(c)
        // Center at origin; a at d=1, b at d=2, c at d=3 (along z).
        tm.space.placeAt(a, Point3D(0.0, 0.0, 1.0))
        tm.space.placeAt(b, Point3D(0.0, 0.0, 2.0))
        tm.space.placeAt(c, Point3D(0.0, 0.0, 3.0))

        val r1 = tm.space.within(Point3D(0.0, 0.0, 0.0), 1.5)
        assertEquals(listOf(a), r1)
        val r3 = tm.space.within(Point3D(0.0, 0.0, 0.0), 5.0)
        // Ordered by distance: a, b, c
        assertEquals(listOf(a, b, c), r3)
    }

    @Test
    fun withinExcludesAgentsOutsideTheSphere() {
        val m = Model("within-out")
        val tm = VolModel(m)
        val inside = tm.Agent("in"); val outside = tm.Agent("out")
        tm.ctx.add(inside); tm.ctx.add(outside)
        tm.space.placeAt(inside, Point3D(1.0, 1.0, 1.0))      // d = √3 ≈ 1.73
        tm.space.placeAt(outside, Point3D(10.0, 10.0, 10.0))  // d = √300 ≈ 17.3
        val r = tm.space.within(Point3D(0.0, 0.0, 0.0), 2.0)
        assertEquals(listOf(inside), r)
        assertFalse(outside in r)
    }

    @Test
    fun neighborsOfExcludesSelf() {
        val m = Model("neighbors")
        val tm = VolModel(m)
        val a = tm.Agent("a"); val b = tm.Agent("b")
        tm.ctx.add(a); tm.ctx.add(b)
        tm.space.placeAt(a, Point3D(5.0, 5.0, 5.0))
        tm.space.placeAt(b, Point3D(6.0, 5.0, 5.0))
        val ns = tm.space.neighborsOf(a, 5.0)
        assertEquals(listOf(b), ns)
        assertFalse(a in ns)
    }

    // ── Spatial-hash parity with a naive linear scan ───────────────────────

    @Test
    fun withinMatchesNaiveLinearScanOnRandomPopulation() {
        // Same parity check as the 2D ContextTest: place a few hundred
        // agents at randomized positions, then verify the hash-backed
        // `within` returns the same set as a brute-force scan.
        val m = Model("parity")
        val tm = VolModel(m)
        val rng = java.util.Random(0xC0FFEEL)
        val agents = (0 until 200).map { tm.Agent("ag-$it") }
        for (a in agents) {
            tm.ctx.add(a)
            tm.space.placeAt(
                a,
                rng.nextDouble() * 100.0,
                rng.nextDouble() * 100.0,
                rng.nextDouble() * 50.0,
            )
        }

        for (trial in 0 until 20) {
            val center = Point3D(
                rng.nextDouble() * 100.0,
                rng.nextDouble() * 100.0,
                rng.nextDouble() * 50.0,
            )
            val radius = rng.nextDouble() * 25.0 + 1.0
            val viaHash = tm.space.within(center, radius).toSet()
            val viaLinear = agents.filter {
                tm.space.positionOf(it)!!.distanceTo(center) <= radius
            }.toSet()
            assertEquals(
                viaLinear, viaHash,
                "spatial-hash mismatch at trial $trial (center=$center, r=$radius)",
            )
        }
    }

    // ── nearest ─────────────────────────────────────────────────────────────

    @Test
    fun nearestRejectsNegativeK() {
        val m = Model("nearest-neg")
        val tm = VolModel(m)
        assertThrows<IllegalArgumentException> {
            tm.space.nearest(Point3D(0.0, 0.0, 0.0), -1)
        }
    }

    @Test
    fun nearestKEqualsZeroReturnsEmpty() {
        val m = Model("nearest-zero")
        val tm = VolModel(m)
        val a = tm.Agent("a"); tm.ctx.add(a); tm.space.placeAt(a, 0.0, 0.0, 0.0)
        assertEquals(emptyList(), tm.space.nearest(Point3D(0.0, 0.0, 0.0), 0))
    }

    @Test
    fun nearestReturnsKClosestInOrder() {
        val m = Model("nearest-k")
        val tm = VolModel(m)
        val a = tm.Agent("a"); val b = tm.Agent("b"); val c = tm.Agent("c")
        tm.ctx.add(a); tm.ctx.add(b); tm.ctx.add(c)
        tm.space.placeAt(a, Point3D(0.0, 0.0, 1.0))
        tm.space.placeAt(b, Point3D(0.0, 0.0, 5.0))
        tm.space.placeAt(c, Point3D(0.0, 0.0, 10.0))

        val n2 = tm.space.nearest(Point3D(0.0, 0.0, 0.0), 2)
        assertEquals(listOf(a, b), n2)
    }

    @Test
    fun nearestKGreaterThanPopulationReturnsAll() {
        val m = Model("nearest-overflow")
        val tm = VolModel(m)
        val a = tm.Agent("a"); val b = tm.Agent("b")
        tm.ctx.add(a); tm.ctx.add(b)
        tm.space.placeAt(a, 0.0, 0.0, 1.0); tm.space.placeAt(b, 0.0, 0.0, 5.0)
        val all = tm.space.nearest(Point3D(0.0, 0.0, 0.0), 10)
        assertEquals(listOf(a, b), all)
    }

    // ── Diagnostics ────────────────────────────────────────────────────────

    @Test
    fun occupiedBucketCountAndMaxBucketOccupancy() {
        val m = Model("buckets")
        val tm = VolModel(m)   // cellSize default = 5.0
        // Place three agents in the same cell, two in distinct cells.
        val a = tm.Agent("a"); val b = tm.Agent("b"); val c = tm.Agent("c")
        val d = tm.Agent("d"); val e = tm.Agent("e")
        for (ag in listOf(a, b, c, d, e)) tm.ctx.add(ag)
        tm.space.placeAt(a, 1.0, 1.0, 1.0)
        tm.space.placeAt(b, 2.0, 2.0, 2.0)
        tm.space.placeAt(c, 3.0, 3.0, 3.0)   // all three within cell (0,0,0)
        tm.space.placeAt(d, 10.0, 0.0, 0.0)  // cell (2,0,0)
        tm.space.placeAt(e, 0.0, 10.0, 0.0)  // cell (0,2,0)
        assertEquals(3, tm.space.occupiedBucketCount)
        assertEquals(3, tm.space.maxBucketOccupancy)
    }
}
