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

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 *  Tests for the validation delegates in `PropertyValidation.kt` and their
 *  integration with the agent package's mutable defaults.
 *
 *  **Phase C10.** This suite used to exercise the delegates through
 *  `PedestrianCrowdExample` and `WarehouseAGVExample` — a `KSLCore` unit test that
 *  broke whenever an *example* changed a default value, and conceptually inverted
 *  the dependency direction, since examples are meant to be a sink. Those cases are
 *  now driven through [Fixture], a stand-in owned by this test that reproduces the
 *  same shape: a mutable `Defaults` companion plus instance properties initialised
 *  from it.
 *
 *  **Phase C9.** The fixture also exercises every factory the family offers.
 *  `atLeast` and `inRange` previously had no use anywhere — main, examples or tests.
 *  Unlike a construct that is wrong, these are correct and complete the family, so
 *  they are wired up rather than withdrawn; the inclusive/exclusive pairing of
 *  `atLeast` against `greaterThan` is precisely why both earn their place, and that
 *  boundary is what the tests below pin.
 *
 *  Each test that mutates a global `Defaults` value restores it in `@AfterEach`, so
 *  other tests in the same JVM see consistent values.
 */
class PropertyValidationTest {

    /**
     *  A test-owned stand-in for the shape the agent examples use: mutable global
     *  defaults, and per-instance properties seeded from them at construction. Every
     *  delegate in the family appears exactly once.
     */
    private class Fixture {
        var mass: Double by positive(Defaults.mass)
        var force: Double by nonNegative(Defaults.force)
        var chance: Double by probability(Defaults.chance)
        var capacity: Int by positive(Defaults.capacity)
        var backlog: Int by nonNegative(Defaults.backlog)
        var floorInclusive: Double by atLeast(2.0, Defaults.floorInclusive)
        var floorExclusive: Double by greaterThan(1.0, Defaults.floorExclusive)
        var bounded: Double by inRange(0.0..10.0, Defaults.bounded)

        companion object Defaults {
            var mass: Double by positive(80.0)
            var force: Double by nonNegative(2000.0)
            var chance: Double by probability(0.2)
            var capacity: Int by positive(3)
            var backlog: Int by nonNegative(0)
            var floorInclusive: Double by atLeast(2.0, 5.0)
            var floorExclusive: Double by greaterThan(1.0, 2.0)
            var bounded: Double by inRange(0.0..10.0, 5.0)
        }
    }

    // Capture and restore each touched default.
    private val savedTravelStepSize: Double = Travel.Defaults.stepSize
    private val savedFlowCellSize: Double = FlowField.Defaults.cellSize
    private val savedResourceCapacity: Int = AgentResource.Defaults.capacity
    private val savedNetEdgeWeight: Double = NetworkProjection.Defaults.edgeWeight
    private val savedNearestGrowth: Double = ContinuousProjection.Defaults.nearestRadiusGrowthFactor
    private val savedFixtureMass: Double = Fixture.Defaults.mass

    @AfterEach
    fun restoreDefaults() {
        Travel.Defaults.stepSize = savedTravelStepSize
        FlowField.Defaults.cellSize = savedFlowCellSize
        AgentResource.Defaults.capacity = savedResourceCapacity
        NetworkProjection.Defaults.edgeWeight = savedNetEdgeWeight
        ContinuousProjection.Defaults.nearestRadiusGrowthFactor = savedNearestGrowth
        Fixture.Defaults.mass = savedFixtureMass
    }

    // ── Core-library defaults reject invalid values ─────────────────────────

    @Test
    fun travelDefaultsStepSizeRejectsNonPositive() {
        assertThrows<IllegalArgumentException> { Travel.Defaults.stepSize = 0.0 }
        assertThrows<IllegalArgumentException> { Travel.Defaults.stepSize = -1.0 }
    }

    @Test
    fun flowFieldDefaultsCellSizeRejectsNonPositive() {
        assertThrows<IllegalArgumentException> { FlowField.Defaults.cellSize = 0.0 }
        assertThrows<IllegalArgumentException> { FlowField.Defaults.cellSize = -0.5 }
    }

    @Test
    fun agentResourceDefaultsCapacityRejectsNonPositive() {
        assertThrows<IllegalArgumentException> { AgentResource.Defaults.capacity = 0 }
        assertThrows<IllegalArgumentException> { AgentResource.Defaults.capacity = -1 }
    }

    @Test
    fun networkProjectionDefaultsEdgeWeightRejectsNegative() {
        // Non-negative: zero is OK (zero-cost edges are valid in some graphs).
        NetworkProjection.Defaults.edgeWeight = 0.0
        assertEquals(0.0, NetworkProjection.Defaults.edgeWeight)
        assertThrows<IllegalArgumentException> { NetworkProjection.Defaults.edgeWeight = -0.1 }
    }

    @Test
    fun continuousProjectionNearestGrowthFactorRejectsAtMostOne() {
        // Must be STRICTLY greater than 1.0.
        assertThrows<IllegalArgumentException> { ContinuousProjection.Defaults.nearestRadiusGrowthFactor = 1.0 }
        assertThrows<IllegalArgumentException> { ContinuousProjection.Defaults.nearestRadiusGrowthFactor = 0.5 }
        ContinuousProjection.Defaults.nearestRadiusGrowthFactor = 1.5  // valid
    }

    // ── Per-call require()s catch invalid values even when global is valid ─

    @Test
    fun networkProjectionConnectRejectsNegativeWeightAtCallSite() {
        // Global default is valid; per-call argument violates.
        val model = ksl.simulation.Model("PVT-net")
        val am = NetTestModel(model)
        assertThrows<IllegalArgumentException> { am.net.connect(am.a, am.b, weight = -1.0) }
    }

    private class NetTestModel(parent: ksl.simulation.Model) : AgentModel(parent, "am") {
        val ctx: Context<Agent> = Context("nodes")
        val net: NetworkProjection<Agent> = NetworkProjection(ctx)
        val a: Agent = Agent("a").also { ctx.add(it) }
        val b: Agent = Agent("b").also { ctx.add(it) }
    }

    // ── C9: every factory, at its boundary ──────────────────────────────────

    @Test
    @DisplayName("C9: positive excludes zero; nonNegative includes it")
    fun positiveAndNonNegativeDifferAtZero() {
        val f = Fixture()
        assertThrows<IllegalArgumentException> { f.mass = 0.0 }
        assertThrows<IllegalArgumentException> { f.mass = -1.0 }
        f.force = 0.0
        assertEquals(0.0, f.force, "non-negative admits zero")
        assertThrows<IllegalArgumentException> { f.force = -0.001 }
    }

    @Test
    @DisplayName("C9: the Int overloads validate the same way")
    fun intOverloadsValidate() {
        val f = Fixture()
        assertThrows<IllegalArgumentException> { f.capacity = 0 }
        assertThrows<IllegalArgumentException> { f.capacity = -2 }
        f.backlog = 0
        assertEquals(0, f.backlog, "non-negative Int admits zero")
        assertThrows<IllegalArgumentException> { f.backlog = -1 }
    }

    @Test
    @DisplayName("C9: probability admits both endpoints and nothing outside")
    fun probabilityAdmitsBothEndpoints() {
        val f = Fixture()
        f.chance = 0.0
        assertEquals(0.0, f.chance)
        f.chance = 1.0
        assertEquals(1.0, f.chance)
        assertThrows<IllegalArgumentException> { f.chance = -0.000001 }
        assertThrows<IllegalArgumentException> { f.chance = 1.000001 }
    }

    /**
     *  The pair that justifies having both: `atLeast` is inclusive of its bound and
     *  `greaterThan` is exclusive. A model that treats the two as interchangeable
     *  gets a silently different admissible set at exactly one value.
     */
    @Test
    @DisplayName("C9: atLeast admits its bound, greaterThan rejects it")
    fun atLeastIsInclusiveAndGreaterThanIsNot() {
        val f = Fixture()
        f.floorInclusive = 2.0
        assertEquals(2.0, f.floorInclusive, "atLeast(2.0) must admit exactly 2.0")
        assertThrows<IllegalArgumentException> { f.floorInclusive = 1.999999 }

        assertThrows<IllegalArgumentException> { f.floorExclusive = 1.0 }
        f.floorExclusive = 1.000001
        assertEquals(1.000001, f.floorExclusive, "greaterThan(1.0) must admit just above 1.0")
    }

    @Test
    @DisplayName("C9: inRange admits both endpoints and nothing outside")
    fun inRangeAdmitsBothEndpoints() {
        val f = Fixture()
        f.bounded = 0.0
        assertEquals(0.0, f.bounded)
        f.bounded = 10.0
        assertEquals(10.0, f.bounded)
        assertThrows<IllegalArgumentException> { f.bounded = -0.001 }
        assertThrows<IllegalArgumentException> { f.bounded = 10.001 }
    }

    /**
     *  The message must name the property and the offending value, or a modeller
     *  gets a bare `IllegalArgumentException` from deep inside a delegate with no
     *  clue which of a dozen parameters was wrong.
     */
    @Test
    @DisplayName("C9: the rejection message names the property and the value")
    fun rejectionMessageIsInformative() {
        val f = Fixture()
        val message = assertThrows<IllegalArgumentException> { f.mass = -5.0 }.message
        assertEquals(true, message?.contains("mass"), "should name the property; was: $message")
        assertEquals(true, message?.contains("-5"), "should name the value; was: $message")
    }

    // ── C10: Defaults semantics, on a fixture this test owns ────────────────

    @Test
    @DisplayName("C10: an instance setter validates independently of a valid default")
    fun instanceSetterValidatesIndependentlyOfDefaults() {
        val f = Fixture()
        assertThrows<IllegalArgumentException> { f.mass = -1.0 }
        f.mass = 75.0
        assertEquals(75.0, f.mass)
    }

    @Test
    @DisplayName("C10: changing a default affects subsequently built instances")
    fun changingDefaultsAffectsSubsequentInstances() {
        Fixture.Defaults.mass = 90.0
        assertEquals(90.0, Fixture().mass)
    }

    @Test
    @DisplayName("C10: changing a default does not reach back into existing instances")
    fun changingDefaultsDoesNotRetroactivelyAffectExistingInstances() {
        val f = Fixture()
        val original = f.mass
        Fixture.Defaults.mass = 100.0
        assertEquals(original, f.mass, "an existing instance keeps the value it was built with")
    }

    @Test
    @DisplayName("C10: a default is itself validated on assignment")
    fun defaultsValidateOnAssignment() {
        assertThrows<IllegalArgumentException> { Fixture.Defaults.mass = 0.0 }
        assertThrows<IllegalArgumentException> { Fixture.Defaults.mass = -1.0 }
    }
}
