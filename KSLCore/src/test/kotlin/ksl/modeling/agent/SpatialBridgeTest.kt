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

import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 *  Phase 4.1 regression tests: the agent-layer ↔ spatial-layer
 *  bridge. Verifies:
 *   - [LocationIfc.toPoint2D] extracts coordinates from 2D
 *     Cartesian spatial-layer locations and returns null for
 *     non-Cartesian ones.
 *   - [ProjectionSpatialModel] uses the projection's distance
 *     metric, including torus wrap.
 *   - Locations created by the adapter round-trip through
 *     `toPoint2D`.
 *   - Cross-adapter location validity: locations from one
 *     `ProjectionSpatialModel` are not valid in another.
 */
class SpatialBridgeTest {

    private class BridgeModel(parent: ModelElement) : AgentModel(parent, "bridge") {
        val context: Context<Agent> = Context("ctx")
        val space: ContinuousProjection<Agent> =
            ContinuousProjection(context, xRange = 0.0..100.0, yRange = 0.0..100.0)
    }

    // ── toPoint2D extraction ───────────────────────────────────────────────

    @Test
    fun toPoint2DExtractsFromEuclidean2DPlanePoint() {
        val plane = Euclidean2DPlane()
        val loc = plane.Point(3.0, 4.0, "p")
        val p = loc.toPoint2D()
        assertNotNull(p)
        assertEquals(Point2D(3.0, 4.0), p)
    }

    @Test
    fun toPoint2DReturnsNullForDistancesModelLocation() {
        val model = Model("DistancesTest")
        val dm = object : ModelElement(model, "dmTest") {
            val distances = DistancesModel()
        }
        // Use the DistancesModel.Location factory (named location).
        val loc = dm.distances.Location("warehouse")
        // DistancesModel.Location is not a 2D Cartesian location.
        assertNull(loc.toPoint2D())
    }

    @Test
    fun toPoint2DExtractsFromProjectedLocation() {
        val model = Model("ProjectedExtractTest")
        val bm = BridgeModel(model)
        val sm = bm.space.asSpatialModel()
        val loc = sm.location(7.5, 12.25, "p")
        assertEquals(Point2D(7.5, 12.25), loc.toPoint2D())
    }

    // ── ProjectionSpatialModel distance ─────────────────────────────────────

    @Test
    fun projectionSpatialModelDistanceMatchesProjectionDistance() {
        val model = Model("DistanceTest")
        val bm = BridgeModel(model)
        val sm = bm.space.asSpatialModel()
        val a = sm.location(0.0, 0.0)
        val b = sm.location(3.0, 4.0)
        // Direct projection distance.
        val projDist = bm.space.distance(Point2D(0.0, 0.0), Point2D(3.0, 4.0))
        // Distance via the SpatialModel adapter.
        val smDist = sm.distance(a, b)
        assertEquals(5.0, projDist, 1e-9)
        assertEquals(projDist, smDist, 1e-9)
    }

    @Test
    fun projectionSpatialModelTorusDistanceWraps() {
        val model = Model("TorusBridgeTest")
        val bm = object : AgentModel(model, "torus") {
            val context: Context<Agent> = Context("ctx")
            val space: ContinuousProjection<Agent> = ContinuousProjection(
                context, xRange = 0.0..100.0, yRange = 0.0..100.0, torus = true,
            )
        }
        val sm = bm.space.asSpatialModel()
        // Points 99 apart in a 100-unit axis: torus distance = 1.
        val a = sm.location(1.0, 50.0)
        val b = sm.location(100.0, 50.0)
        assertEquals(1.0, sm.distance(a, b), 1e-9)
    }

    // ── compareLocations ───────────────────────────────────────────────────

    @Test
    fun projectionSpatialModelCompareLocationsUsesPointEquality() {
        val model = Model("CompareTest")
        val bm = BridgeModel(model)
        val sm = bm.space.asSpatialModel()
        val a1 = sm.location(5.0, 5.0)
        val a2 = sm.location(5.0, 5.0)
        val b = sm.location(5.0, 5.0001)
        assertTrue(sm.compareLocations(a1, a2), "same-coordinate locations should compare equal")
        assertTrue(!sm.compareLocations(a1, b), "near-but-not-equal coordinates should compare unequal")
    }

    // ── Cross-adapter validity ─────────────────────────────────────────────

    @Test
    fun locationsFromDifferentAdaptersAreNotInterchangeable() {
        val model = Model("CrossAdapterTest")
        val bm = BridgeModel(model)
        val sm1 = bm.space.asSpatialModel()
        val sm2 = bm.space.asSpatialModel()
        // Two adapters from the same projection are still different
        // SpatialModel instances.
        assertNotEquals(sm1, sm2)
        val loc1 = sm1.location(1.0, 2.0)
        val loc2 = sm2.location(1.0, 2.0)
        // distance() requires both locations to belong to the calling
        // spatial model. Mixing them throws.
        try {
            sm1.distance(loc1, loc2)
            error("expected IllegalArgumentException for cross-adapter location use")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ── Default-velocity inheritance ───────────────────────────────────────

    @Test
    fun projectionSpatialModelHasDefaultVelocity() {
        val model = Model("VelocityTest")
        val bm = BridgeModel(model)
        val sm = bm.space.asSpatialModel()
        // Default is ConstantRV.ONE. The exact identity matters less
        // than that a default exists — verified by calling .value.
        assertEquals(1.0, sm.defaultVelocity.value, 1e-9)
    }

    // ── Round-trip through the bridge ──────────────────────────────────────

    @Test
    fun pointRoundTripsThroughSpatialModel() {
        val model = Model("RoundTripTest")
        val bm = BridgeModel(model)
        val sm = bm.space.asSpatialModel()
        val original = Point2D(42.0, 17.5)
        val loc = sm.location(original)
        val extracted = loc.toPoint2D()
        assertEquals(original, extracted)
    }

    // ── NetworkProjection → DistancesModel adapter (Phase 4.3) ─────────────

    private class NetworkAdapterModel(parent: ModelElement) : AgentModel(parent, "netadapter") {
        val context: Context<Agent> = Context("nodes")
        val net: NetworkProjection<Agent> = NetworkProjection(context, directed = false)
        inner class Node(aName: String) : Agent(aName)
    }

    @Test
    fun asDistancesModelExposesDirectEdgesAsDistances() {
        val model = Model("DirectAdapterTest")
        val tm = NetworkAdapterModel(model)
        val a = tm.Node("a"); val b = tm.Node("b")
        listOf(a, b).forEach { tm.context.add(it) }
        tm.net.connect(a, b, weight = 5.0)

        val dm = tm.net.asDistancesModel()
        val locA = dm.location("a"); val locB = dm.location("b")
        assertNotNull(locA); assertNotNull(locB)
        assertEquals(5.0, dm.distance(locA!!, locB!!), 1e-9)
        assertEquals(5.0, dm.distance(locB, locA), 1e-9, "undirected → symmetric distance")
    }

    @Test
    fun asDistancesModelComputesShortestPathDistances() {
        val model = Model("ShortestPathAdapterTest")
        val tm = NetworkAdapterModel(model)
        val a = tm.Node("a"); val b = tm.Node("b"); val c = tm.Node("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        // Line: a -- b -- c, weights 1 and 1.
        tm.net.connect(a, b, weight = 1.0)
        tm.net.connect(b, c, weight = 1.0)

        val dm = tm.net.asDistancesModel()
        val locA = dm.location("a")!!; val locC = dm.location("c")!!
        // Direct edge a-c does not exist, but the DistancesModel
        // entry should reflect the shortest-path distance (1+1=2).
        assertEquals(2.0, dm.distance(locA, locC), 1e-9)
    }

    @Test
    fun asDistancesModelHandlesDirectedAsymmetry() {
        val model = Model("DirectedAdapterTest")
        val tm = object : AgentModel(model, "diradapter") {
            val context: Context<Agent> = Context("nodes")
            val net: NetworkProjection<Agent> = NetworkProjection(context, directed = true)
            inner class Node(aName: String) : Agent(aName)
        }
        val a = tm.Node("a"); val b = tm.Node("b")
        listOf(a, b).forEach { tm.context.add(it) }
        tm.net.connect(a, b, weight = 3.0)
        // No b → a edge.

        val dm = tm.net.asDistancesModel()
        val locA = dm.location("a")!!; val locB = dm.location("b")!!
        assertEquals(3.0, dm.distance(locA, locB), 1e-9)
        try {
            dm.distance(locB, locA)
            error("expected IllegalArgumentException for unreachable pair")
        } catch (e: IllegalArgumentException) {
            // expected: DistancesModel throws on unrecorded pairs
        }
    }

    @Test
    fun asDistancesModelExcludesUnreachableAgents() {
        val model = Model("UnreachableAdapterTest")
        val tm = NetworkAdapterModel(model)
        val a = tm.Node("a"); val b = tm.Node("b"); val c = tm.Node("c"); val d = tm.Node("d")
        listOf(a, b, c, d).forEach { tm.context.add(it) }
        // Two components: {a, b} and {c, d}.
        tm.net.connect(a, b, weight = 1.0)
        tm.net.connect(c, d, weight = 2.0)

        val dm = tm.net.asDistancesModel()
        // Within-component distance: present.
        assertEquals(1.0, dm.distance(dm.location("a")!!, dm.location("b")!!), 1e-9)
        assertEquals(2.0, dm.distance(dm.location("c")!!, dm.location("d")!!), 1e-9)
        // Cross-component pair: not in the DistancesModel.
        try {
            dm.distance(dm.location("a")!!, dm.location("c")!!)
            error("expected IllegalArgumentException for unreachable pair")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun asDistancesModelEmptyNetworkProducesEmptyModel() {
        val model = Model("EmptyAdapterTest")
        val tm = NetworkAdapterModel(model)
        // No agents, no edges.
        val dm = tm.net.asDistancesModel()
        assertTrue(dm.isEmpty)
    }

    @Test
    fun asDistancesModelRejectsCollidingNames() {
        val model = Model("CollidingAdapterTest")
        val tm = NetworkAdapterModel(model)
        val a1 = tm.Node("dupe"); val a2 = tm.Node("dupe")
        listOf(a1, a2).forEach { tm.context.add(it) }
        tm.net.connect(a1, a2, weight = 1.0)
        try {
            tm.net.asDistancesModel()
            error("expected IllegalArgumentException for duplicate names")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // A custom nameOf disambiguates.
        val dm = tm.net.asDistancesModel(nameOf = { "${it.name}_${it.id}" })
        assertEquals(2, dm.locationNames.size)
    }

    @Test
    fun asDistancesModelHandlesWeightedTriangleWithShortcut() {
        val model = Model("TriangleAdapterTest")
        val tm = NetworkAdapterModel(model)
        val a = tm.Node("a"); val b = tm.Node("b"); val c = tm.Node("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        // Direct a-c edge is heavy; routing through b is cheap.
        tm.net.connect(a, c, weight = 10.0)
        tm.net.connect(a, b, weight = 1.0)
        tm.net.connect(b, c, weight = 1.0)
        val dm = tm.net.asDistancesModel()
        // DistancesModel a-c should reflect the SHORTEST PATH (2.0),
        // not the direct-edge weight (10.0).
        assertEquals(2.0, dm.distance(dm.location("a")!!, dm.location("c")!!), 1e-9)
    }
}
