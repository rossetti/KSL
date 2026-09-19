/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.modeling.guidedpath

import ksl.modeling.agv.AgvSystem
import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.policies.assignmentPolicyNames
import ksl.modeling.fleet.policies.createAssignmentPolicy
import ksl.modeling.fleet.policies.nameOfAssignmentPolicy
import ksl.modeling.guidedpath.rules.BoundedBatchArbiter
import ksl.modeling.guidedpath.rules.MoveToStagingAreaRule
import ksl.modeling.guidedpath.rules.crossingArbiterNames
import ksl.modeling.guidedpath.rules.idleDispositionRuleNames
import ksl.modeling.guidedpath.rules.transporterAllocationRuleNames
import ksl.modeling.guidedpath.rules.zoneContentionRuleNames
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  The rules a study may select by name, and the promise each name makes.
 *
 *  A `@KSLStringControl` declares its permitted values as a **literal array**, because an annotation
 *  argument has to be a compile-time constant. The factory that turns a name into a rule is ordinary
 *  code. So the two can drift: add a rule to one and not the other and the control offers a value
 *  that raises, or refuses one that would work. Nothing would say so. These tests are what say so.
 *
 *  They also pin the line the package draws about *which* rules get names. A rule is nameable only
 *  when its family determines it completely. A batching window, a contract-net deadline, a bounded
 *  batch size -- those are not tunings of a policy, they are the policy, and a name standing for one
 *  parameterisation would let a study vary the label while freezing the number, then report the
 *  result as a comparison of rules. The parameterised rules are therefore absent from every
 *  `allowedValues` here, and the reading side of each control reports them by `toString` so a model
 *  using one can still say what it is using.
 */
class RuleNameControlTest {

    /** A network carries the zone state of one system, so each fixture builds its own. */
    private fun ring(name: String): GuidedPathNetwork = GuidedPathNetwork.builder(name)
        .intersection("A", x = 0.0, y = 0.0)
        .intersection("B", x = 100.0, y = 0.0)
        .link("AB", "A", "B", length = 100.0, zoneLength = 10.0, beginDirection = 0.0)
        .link("BA", "B", "A", length = 100.0, zoneLength = 10.0, beginDirection = 180.0)
        .station("P", "A")
        .build()

    /** The passive side: a space, a pool and a crossing, each with a rule to name. */
    private inner class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val network: GuidedPathNetwork = ring("PassiveRing")

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart")
        val pool = GuidedTransporterPoolWithQ(this, system, listOf(cart), name = "Pool")
        val crossing = ZoneCrossing(this, system, listOf(network.zone("AB.Zone2")!!), name = "Walkway")
    }

    /** The active side, whose dispatcher carries the assignment policy. */
    private inner class AgvShop(parent: ModelElement) : ProcessModel(parent, "AgvShop") {
        val network: GuidedPathNetwork = ring("ActiveRing")

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
    }

    private fun shop(): Shop = Shop(Model("RuleNames"))

    private fun agvShop(): AgvShop = AgvShop(Model("RuleNamesAgv"))

    @Test
    @DisplayName("every control offers exactly the names its factory knows")
    fun allowedValuesMatchTheFactories() {
        val passive = shop().model
        val active = agvShop().model
        for ((m, key, names) in listOf(
            Triple(passive, "Sys.zoneContentionRuleName", zoneContentionRuleNames),
            Triple(passive, "Pool.allocationRuleName", transporterAllocationRuleNames),
            Triple(passive, "Pool.idleDispositionRuleName", idleDispositionRuleNames),
            Triple(passive, "Walkway.arbiterName", crossingArbiterNames),
            Triple(active, "Agv:Dispatcher.assignmentPolicyName", assignmentPolicyNames)
        )) {
            val control = assertNotNull(
                m.controls().stringControl(key), "the control '$key' must exist"
            )
            assertEquals(
                names, control.allowedValues.toList(),
                "'$key' must offer exactly the names its factory knows, in the same order; a value " +
                        "declared on the annotation but absent from the factory raises when set"
            )
        }
    }

    @Test
    @DisplayName("every offered name builds a rule, and reads back as the same name")
    fun everyNameRoundTrips() {
        val s = shop()
        for (name in zoneContentionRuleNames) {
            s.system.zoneContentionRuleName = name
            assertEquals(name, s.system.zoneContentionRuleName)
        }
        for (name in transporterAllocationRuleNames) {
            s.pool.allocationRuleName = name
            assertEquals(name, s.pool.allocationRuleName)
        }
        for (name in idleDispositionRuleNames) {
            s.pool.idleDispositionRuleName = name
            assertEquals(name, s.pool.idleDispositionRuleName)
        }
        for (name in crossingArbiterNames) {
            s.crossing.arbiterName = name
            assertEquals(name, s.crossing.arbiterName)
        }
        val a = agvShop()
        for (name in assignmentPolicyNames) {
            a.agv.dispatcher.assignmentPolicyName = name
            assertEquals(name, a.agv.dispatcher.assignmentPolicyName)
        }
    }

    @Test
    @DisplayName("a name builds a fresh rule, so turn-taking state is never inherited")
    fun eachNameBuildsAFreshRule() {
        val s = shop()
        s.pool.allocationRuleName = "Cyclical"
        val first = s.pool.allocationRule
        s.pool.allocationRuleName = "Cyclical"
        assertTrue(
            first !== s.pool.allocationRule,
            "selecting a rule by name must make a new one: a rule that takes turns would otherwise " +
                    "carry the end of one configuration into the next"
        )
    }

    @Test
    @DisplayName("an unknown name is refused, and says what is allowed")
    fun unknownNamesAreRefused() {
        val s = shop()
        val e = assertFailsWith<IllegalArgumentException> { s.pool.idleDispositionRuleName = "GoHome" }
        assertTrue(
            e.message!!.contains("ParkInPlace") && e.message!!.contains("ReturnToHomeBase"),
            "the refusal must name the alternatives, but said: ${e.message}"
        )
        assertFailsWith<IllegalArgumentException> { s.crossing.arbiterName = "BoundedBatch" }
        assertFailsWith<IllegalArgumentException> { s.system.zoneContentionRuleName = "LIFO" }
        assertFailsWith<IllegalArgumentException> {
            agvShop().agv.dispatcher.assignmentPolicyName = "ContractNet"
        }
    }

    @Test
    @DisplayName("a rule no name stands for is still readable, by what it is")
    fun parameterisedRulesReadBack() {
        val s = shop()
        // The point of the boundary: these are set as objects, and the control still reports them
        // rather than lying about which named rule is in force.
        s.crossing.arbiter = BoundedBatchArbiter(batchSize = 2, maxWait = 5.0)
        assertTrue(
            "BoundedBatch" in s.crossing.arbiterName,
            "an arbiter with no name must read back as itself, but read '${s.crossing.arbiterName}'"
        )
        s.pool.idleDispositionRule = MoveToStagingAreaRule("P")
        assertTrue(
            "MoveToStagingArea" in s.pool.idleDispositionRuleName,
            "a staging rule must read back as itself, but read '${s.pool.idleDispositionRuleName}'"
        )
    }

    @Test
    @DisplayName("a rule cannot be swapped out from under a running model")
    fun rulesAreFixedWhileRunning() {
        val s = shop()
        s.model.numberOfReplications = 1
        s.model.lengthOfReplication = 10.0
        s.model.simulate()
        // Not running once the run is over, so this is the guard's negative case rather than a
        // claim that the setters are unusable.
        s.pool.allocationRuleName = "Furthest"
        assertEquals("Furthest", s.pool.allocationRuleName)
    }
}
