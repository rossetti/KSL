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

import ksl.examples.general.agent.PedestrianCrowdExample
import ksl.examples.general.agent.WarehouseAGVExample
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 *  Tests for the validation delegates in `PropertyValidation.kt` and
 *  their integration with the agent package's mutable defaults.
 *
 *  Each test that mutates a global `Defaults` value restores it in
 *  `@AfterEach` to prevent leakage across tests.
 */
class PropertyValidationTest {

    // Capture and restore each touched default so other tests in the
    // same JVM see consistent values.
    private val savedTravelStepSize: Double = Travel.Defaults.stepSize
    private val savedFlowCellSize: Double = FlowField.Defaults.cellSize
    private val savedResourceCapacity: Int = AgentResource.Defaults.capacity
    private val savedNetEdgeWeight: Double = NetworkProjection.Defaults.edgeWeight
    private val savedNearestGrowth: Double = ContinuousProjection.Defaults.nearestRadiusGrowthFactor
    private val savedPedMass: Double = PedestrianCrowdExample.Defaults.mass
    private val savedPedAPed: Double = PedestrianCrowdExample.Defaults.aPed
    private val savedAGVLow: Double = WarehouseAGVExample.Defaults.lowBatteryThreshold

    @AfterEach
    fun restoreDefaults() {
        Travel.Defaults.stepSize = savedTravelStepSize
        FlowField.Defaults.cellSize = savedFlowCellSize
        AgentResource.Defaults.capacity = savedResourceCapacity
        NetworkProjection.Defaults.edgeWeight = savedNetEdgeWeight
        ContinuousProjection.Defaults.nearestRadiusGrowthFactor = savedNearestGrowth
        PedestrianCrowdExample.Defaults.mass = savedPedMass
        PedestrianCrowdExample.Defaults.aPed = savedPedAPed
        WarehouseAGVExample.Defaults.lowBatteryThreshold = savedAGVLow
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

    // ── Example-class defaults reject invalid values ───────────────────────

    @Test
    fun pedestrianMassDefaultRejectsNonPositive() {
        assertThrows<IllegalArgumentException> { PedestrianCrowdExample.Defaults.mass = 0.0 }
        assertThrows<IllegalArgumentException> { PedestrianCrowdExample.Defaults.mass = -10.0 }
    }

    @Test
    fun pedestrianAPedDefaultRejectsNegativeButAllowsZero() {
        // Force constants are non-negative (zero = turn off).
        PedestrianCrowdExample.Defaults.aPed = 0.0
        assertEquals(0.0, PedestrianCrowdExample.Defaults.aPed)
        assertThrows<IllegalArgumentException> { PedestrianCrowdExample.Defaults.aPed = -100.0 }
    }

    @Test
    fun warehouseLowBatteryThresholdRejectsOutOfRange() {
        // Probability constraint: [0, 1].
        WarehouseAGVExample.Defaults.lowBatteryThreshold = 0.0  // valid
        WarehouseAGVExample.Defaults.lowBatteryThreshold = 1.0  // valid
        assertThrows<IllegalArgumentException> { WarehouseAGVExample.Defaults.lowBatteryThreshold = -0.01 }
        assertThrows<IllegalArgumentException> { WarehouseAGVExample.Defaults.lowBatteryThreshold = 1.5 }
    }

    // ── Per-instance setters validate independently of Defaults ────────────

    @Test
    fun pedestrianInstanceSetterRejectsInvalidEvenWhenDefaultIsValid() {
        val model = ksl.simulation.Model("PVT-ped")
        val sys = PedestrianCrowdExample(model, "ped")
        assertThrows<IllegalArgumentException> { sys.mass = -1.0 }
        assertThrows<IllegalArgumentException> { sys.tau = 0.0 }
        // A valid assignment still works.
        sys.mass = 75.0
        assertEquals(75.0, sys.mass)
    }

    // ── Default constants from Defaults are reflected in new instances ─────

    @Test
    fun changingDefaultsAffectsSubsequentInstances() {
        PedestrianCrowdExample.Defaults.mass = 90.0
        val model = ksl.simulation.Model("PVT-default")
        val sys = PedestrianCrowdExample(model, "ped")
        assertEquals(90.0, sys.mass)
    }

    @Test
    fun changingDefaultsDoesNotRetroactivelyAffectExistingInstances() {
        val model = ksl.simulation.Model("PVT-no-retro")
        val sys = PedestrianCrowdExample(model, "ped")
        val originalMass = sys.mass
        PedestrianCrowdExample.Defaults.mass = 100.0
        assertEquals(originalMass, sys.mass)  // not retroactively changed
    }
}
