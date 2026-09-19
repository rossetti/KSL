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
package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.ConsolidatingPolicy
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.modeling.variable.RandomVariable
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  **Does the capacity do any work, and can a modeller tell?**
 *
 *  Multi-load without a way to report it is the asymmetry this whole body of work was written to
 *  avoid, so the capability and the measurement land together. These tests are the acceptance for
 *  the measurement half, and the standard they are held to is that each row answers a question no
 *  other row answers.
 *
 *  **The sharpest of them is the trap.** `FracTimeTransporting` reads 1.0 whether a vehicle carries
 *  one load or four -- it is a fraction of *time*, not of *capacity* -- so a fleet moving one pallet
 *  at a time in a four-pallet body reports as fully utilised. `CapacityUtilization` is the row that
 *  answers the question people read the first one as answering, and the test below shows the two
 *  disagreeing on the same run, which is the only way to show that the second earns its place.
 *
 *  **The study is a tradeoff, not a win.** More capacity means less empty running, and it also means
 *  a load waits while the vehicle collects somebody else's. A set of metrics that could show only
 *  the first half would be a set that flattered the feature.
 */
class CapacityStatisticsTest {

    private class Shop(
        parent: ModelElement,
        capacity: Int,
        val arrivalRate: Double = 60.0
    ) : ProcessModel(parent, "Shop") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 25.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 25.0, beginDirection = 90.0)
            .link("CD", "C", "D", length = 100.0, zoneLength = 25.0, beginDirection = 180.0)
            .link("DA", "D", "A", length = 100.0, zoneLength = 25.0, beginDirection = 270.0)
            // A dead end off B. Serving it means going in and coming back out, so a load that is
            // carried along while the vehicle does so rides further than it would alone. On a
            // one-way loop alone this is impossible: every route is the only route, so consolidating
            // costs nobody anything and the "shared ride is a longer ride" cost simply does not
            // exist. The spur is the smallest thing that makes it exist.
            .link("Spur", "B", "E", length = 100.0, zoneLength = 25.0,
                  beginDirection = 45.0, type = LinkType.SPUR)
            .build()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network,
            assignmentPolicy = BatchedAssignmentPolicy(window = 20.0, inner = ConsolidatingPolicy()),
            name = "Agv"
        )

        val cart = AgvVehicle(
            agv, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart", loadCapacity = capacity
        )

        private val tba = RandomVariable(this, ExponentialRV(arrivalRate, 1))

        var delivered: Int = 0
        var totalTimeInSystem: Double = 0.0
        var totalWaitForVehicle: Double = 0.0
        var totalTimeAboard: Double = 0.0

        private var alternate = 0

        inner class Load : Entity("Load") {
            // Two origin-destination pairs, alternating. With one pair a shared ride costs nobody
            // anything -- everybody is collected in the same place and set down in the same place,
            // so consolidating involves no detour and the ride cannot lengthen. Two pairs is the
            // smallest fixture in which carrying somebody else's load makes yours take longer,
            // which is the half of the tradeoff a single-pair fixture cannot show.
            private val from = "A"
            private val second = alternate++ % 2 == 1
            private val to = if (second) "E" else "C"

            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from)
                val r = transportByFleet(agv, to, origin = from)
                totalTimeInSystem += r.totalTime
                totalWaitForVehicle += r.waitForAssignment + r.waitForArrival
                totalTimeAboard += r.timeAboard
                delivered++
            }
        }

        inner class Generator : Entity("Gen") {
            val p = process(isDefaultProcess = true) {
                while (true) {
                    delay(tba.value)
                    activate(Load().p)
                }
            }
        }

        override fun initialize() {
            activate(Generator().p)
        }

        val meanTimeInSystem: Double
            get() = if (delivered == 0) Double.NaN else totalTimeInSystem / delivered
        val meanWaitForVehicle: Double
            get() = if (delivered == 0) Double.NaN else totalWaitForVehicle / delivered
        val meanTimeAboard: Double
            get() = if (delivered == 0) Double.NaN else totalTimeAboard / delivered
    }

    private fun run(capacity: Int, arrivalRate: Double = 60.0): Shop {
        val m = Model("Capacity$capacity")
        val shop = Shop(m, capacity, arrivalRate)
        m.numberOfReplications = 1
        m.lengthOfReplication = 5000.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("A single-load vehicle registers no capacity rows at all")
    fun noRowsForAFleetThatCannotUseThem() {
        val shop = run(capacity = 1)
        assertNull(shop.cart.body.capacityUtilization, "a row for a capacity of one asks a question the model cannot have")
        assertNull(shop.cart.body.fracTimeAtCapacity)
        assertNull(shop.cart.body.numLoadsAboardResponse)
        assertNull(shop.cart.body.loadsPerLoadedMove)
        assertNull(shop.cart.loadsPerTour)
    }

    @Test
    @DisplayName("A multi-load vehicle registers them, and they are populated")
    fun theRowsExistAndAreFed() {
        val shop = run(capacity = 4)
        assertNotNull(shop.cart.body.capacityUtilization)
        assertNotNull(shop.cart.body.fracTimeAtCapacity)
        assertNotNull(shop.cart.body.numLoadsAboardResponse)
        val perMove = assertNotNull(shop.cart.body.loadsPerLoadedMove)
        val perTour = assertNotNull(shop.cart.loadsPerTour)
        assertTrue(perMove.withinReplicationStatistic.count > 0.0, "no loaded move was ever observed")
        assertTrue(perTour.withinReplicationStatistic.count > 0.0, "no tour was ever observed")
        assertTrue(shop.delivered > 10, "the fixture delivered too little to say anything: ${shop.delivered}")
    }

    @Test
    @DisplayName("Capacity utilization and FracTimeTransporting disagree, which is why both exist")
    fun utilizationIsNotTheSameAsTransporting() {
        // The trap, demonstrated rather than described. A vehicle that holds four and usually
        // carries fewer is "transporting" for the whole of every loaded move, and using a fraction
        // of its room for the whole of the same interval.
        val shop = run(capacity = 4)
        val transporting = shop.cart.fracTimeTransporting.withinReplicationStatistic.weightedAverage
        val utilization = shop.cart.body.capacityUtilization!!.withinReplicationStatistic.weightedAverage
        assertTrue(transporting > 0.0, "the fixture never carried anything")
        assertTrue(
            utilization < transporting - 1e-9,
            "capacity utilization ($utilization) was not below the transporting fraction " +
                    "($transporting). If they agree, the vehicle was full whenever it moved and " +
                    "this fixture cannot show the difference the two rows exist to show"
        )
    }

    @Test
    @DisplayName("The rows are consistent with each other: mean loads, utilization, and being full")
    fun theRowsPartition() {
        val shop = run(capacity = 4)
        val mean = shop.cart.body.numLoadsAboardResponse!!.withinReplicationStatistic.weightedAverage
        val util = shop.cart.body.capacityUtilization!!.withinReplicationStatistic.weightedAverage
        val full = shop.cart.body.fracTimeAtCapacity!!.withinReplicationStatistic.weightedAverage
        assertEquals(
            mean / 4.0, util, 1e-9,
            "utilization must be the mean number aboard over the capacity, or the two rows are " +
                    "measuring different things while appearing to measure one"
        )
        assertTrue(full <= util + 1e-9, "a vehicle cannot be full for more of the time than it is used")
        assertTrue(mean in 0.0..4.0)
    }

    @Test
    @DisplayName("The study: the ride lengthens, the empty running falls — the tradeoff, isolated")
    fun theStudyShowsBothHalves() {
        val one = run(capacity = 1)
        val two = run(capacity = 2)
        val four = run(capacity = 4)

        // The cost, isolated. A load collected first waits aboard while the vehicle collects
        // another and detours to set it down, so its ride is longer than it would have been alone.
        // This is the half a metric set could most easily hide, because the *total* need not rise.
        assertTrue(
            two.meanTimeAboard > one.meanTimeAboard && four.meanTimeAboard > one.meanTimeAboard,
            "time aboard did not lengthen: ${one.meanTimeAboard} at one, ${two.meanTimeAboard} at " +
                    "two, ${four.meanTimeAboard} at four. If a shared ride costs its riders nothing " +
                    "then either nothing is being shared or the layout has no detour to make, and " +
                    "the study is measuring a fixture rather than a tradeoff"
        )

        // The payoff. Fewer trips out to collect, because more is collected per trip.
        assertTrue(
            four.cart.fracTimeMovingEmpty.withinReplicationStatistic.weightedAverage <
                    one.cart.fracTimeMovingEmpty.withinReplicationStatistic.weightedAverage,
            "empty running did not fall with capacity, so the capacity is doing nothing"
        )

        // And the row that says the room was used rather than merely present.
        assertTrue(
            four.cart.body.loadsPerLoadedMove!!.withinReplicationStatistic.weightedAverage > 1.0,
            "loaded moves averaged one load at a capacity of four: nothing above can be attributed " +
                    "to consolidation"
        )
    }

    @Test
    @DisplayName("Whether the total rises or falls is a fact about the regime, not a law")
    fun theTotalDependsOnCongestion() {
        // Worth pinning because it is the thing most likely to be assumed. It is tempting to say
        // "a shared ride is a longer ride, so multi-load costs time in system". The ride is indeed
        // longer -- the test above asserts it -- but time in system is the ride *plus the wait*,
        // and consolidating shortens the wait by getting round to everybody sooner. Which term
        // wins is a property of how loaded the fleet is, and on this fixture the wait wins at every
        // arrival rate tried.
        val busyOne = run(capacity = 1, arrivalRate = 60.0)
        val busyFour = run(capacity = 4, arrivalRate = 60.0)
        val quietOne = run(capacity = 1, arrivalRate = 400.0)
        val quietFour = run(capacity = 4, arrivalRate = 400.0)

        assertTrue(
            busyFour.meanTimeInSystem < busyOne.meanTimeInSystem,
            "under congestion the wait dominates and capacity should help: " +
                    "${busyOne.meanTimeInSystem} against ${busyFour.meanTimeInSystem}"
        )
        // Quiet: there is little to consolidate, so the effect is small either way. What must hold
        // is that the *saving* shrinks as the fleet stops being the constraint.
        val busySaving = busyOne.meanTimeInSystem - busyFour.meanTimeInSystem
        val quietSaving = quietOne.meanTimeInSystem - quietFour.meanTimeInSystem
        assertTrue(
            quietSaving < busySaving,
            "the saving from capacity did not shrink as the fleet stopped being the constraint: " +
                    "$busySaving when busy against $quietSaving when quiet. Capacity is worth most " +
                    "where the vehicle is the bottleneck, and a study that showed a constant gain " +
                    "would be measuring something else"
        )
    }

    @Test
    @DisplayName("Capacity beyond what the traffic presents buys nothing, and a row says so")
    fun capacityBeyondDemandIsIdle() {
        val quiet = run(capacity = 4, arrivalRate = 400.0)
        val full = quiet.cart.body.fracTimeAtCapacity!!.withinReplicationStatistic.weightedAverage
        val perMove = quiet.cart.body.loadsPerLoadedMove!!.withinReplicationStatistic.weightedAverage
        assertTrue(
            full < 0.01,
            "a four-load vehicle on light traffic was full for $full of the time: this fixture is " +
                    "not light traffic"
        )
        assertTrue(
            perMove < 1.5,
            "loaded moves averaged $perMove: with traffic this light there is rarely a second load " +
                    "to take, and the capacity is capacity nobody is using"
        )
    }
}
