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

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.Statistic
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The five figures that say what the guide path cost a delivered load, in **both** paradigms.
 *
 *  These five -- how long the vehicle ran empty to collect the load, how long it ran carrying it,
 *  how much of that it could not claim the space ahead, and how far over how many zones -- are
 *  registered by the space layer, which both subsystems run on. `AgvSystem` publishes them by
 *  delegation, on the argument that the same questions are worth asking of a fleet whichever way it
 *  is dispatched.
 *
 *  They were fed by only one of the two. `collectTransportResult` had a single caller, the passive
 *  `transportBy`, so an active model published five responses that were **permanently empty** while
 *  their documentation described what they contained. Probed on a one-cart model that delivered 74
 *  loads:
 *
 *  ```
 *  timeAboard (own)             count=    74
 *  approachTime                count=     0
 *  rideTime               count=     0
 *  transportBlockedTime         count=     0
 *  zonesTraversedPerTransport   count=     0
 *  routeLengthPerTransport      count=     0
 *  ```
 *
 *  A count of zero is the failure this test exists to prevent, and asserting the count alone would
 *  prevent exactly that and nothing else -- five responses fed with plausible rubbish would satisfy
 *  it. So the same shop is built both ways, as `GateAEquivalenceTest` builds it, and the two
 *  paradigms' answers are held against each other. One cart, so dispatching cannot differ and there
 *  is nothing to contend for: any disagreement is a disagreement about what is being measured.
 *
 *  ## The boundary, which is the part easy to get wrong
 *
 *  Loading and unloading delays are deliberately **non-zero**, and that is what makes two of the
 *  assertions sharp. A vehicle that has arrived is not travelling, so neither delay belongs to a
 *  move time -- but both fall inside the wider intervals the active result reports, and measuring at
 *  the wrong side of one is the natural mistake. Since the delays are constant and every load pays
 *  both, the difference of the means is exactly one delay:
 *
 *  - `mean(waitForArrival) - mean(approachTime)` must be exactly the loading delay;
 *  - `mean(timeAboard) - mean(rideTime)` must be exactly the unloading delay.
 *
 *  Either would fail on an off-by-one-delay, and neither would be satisfied by a response that
 *  merely had observations in it.
 *
 *  ## Where the empty move starts, which is the part that was actually wrong
 *
 *  The first attempt measured the empty move over the **travel leg** -- from the vehicle beginning to
 *  move until it reached the load -- and the two paradigms then disagreed by 2.8%: 11.5108 passive
 *  against 11.1857 active. The passive figure turned out to be exactly `waitForArrival` minus the
 *  loading delay, which says where its clock really starts: at **allocation**, not at the first step.
 *  The 0.33 between them is the vehicle disengaging from whatever it was doing, which the passive
 *  paradigm has no way to separate out and therefore counts.
 *
 *  So the active subsystem measures from the assignment instant too, and the two now agree to the
 *  digit on all five. A shared row has to mean one thing; picking the more defensible-sounding of two
 *  boundaries and leaving the row ambiguous would have replaced an empty statistic with a misleading
 *  one, which is not an improvement.
 */
class PerCarryStatisticsTest {

    private companion object {
        const val HORIZON = 4000.0
        const val MEAN_INTERARRIVAL = 40.0
        const val ARRIVAL_STREAM = 1
        const val VELOCITY = 10.0

        /** Non-zero on purpose: see the boundary argument in the class comment. */
        const val LOADING = 2.0
        const val UNLOADING = 3.0

        /** Below this the comparison is not worth making. */
        const val MIN_COMPLETIONS = 40.0
    }

    /** What one paradigm reported, under names that mean the same thing in each. */
    private class Carry(
        val completions: Double,
        val emptyMove: ResponseCIfc,
        val loadedMove: ResponseCIfc,
        val blocked: ResponseCIfc,
        val zones: ResponseCIfc,
        val distance: ResponseCIfc
    ) {
        val five: Map<String, ResponseCIfc>
            get() = mapOf(
                "approachTime" to emptyMove,
                "rideTime" to loadedMove,
                "transportBlockedTime" to blocked,
                "zonesTraversedPerTransport" to zones,
                "routeLengthPerTransport" to distance
            )
    }

    // ---- the two shops -------------------------------------------------------------------------

    private class PassiveShop(parent: ModelElement) : ProcessModel(parent, "Passive") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(VELOCITY), name = "Cart"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }
        val pool = GuidedTransporterPoolWithQ(
            this, system, listOf(cart), ClosestByNetworkDistanceRule(), ReturnToHomeBaseRule(), "Pool"
        )

        var completions = 0.0

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                guidedTransport(
                    pool, SimpleAgvNetwork.EXIT_STATION,
                    pickupLocation = SimpleAgvNetwork.ENTRY_STATION,
                    loadingDelay = ConstantRV(LOADING), unLoadingDelay = ConstantRV(UNLOADING)
                )
                completions++
            }
        }

        private val tba = ExponentialRV(MEAN_INTERARRIVAL, streamNum = ARRIVAL_STREAM)
        private val generator = EntityGenerator(::Part, tba, tba)

        override fun initialize() {
            completions = 0.0
        }
    }

    private class ActiveShop(parent: ModelElement) : ProcessModel(parent, "Active") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(VELOCITY), name = "Cart"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        /** The wider intervals the active result reports, kept so the boundary can be checked. */
        val waitForArrival = Statistic("waitForArrival")
        val timeAboard = Statistic("timeAboard")
        var completions = 0.0

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val r = transportByFleet(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION,
                    loadingDelay = ConstantRV(LOADING), unLoadingDelay = ConstantRV(UNLOADING)
                )
                waitForArrival.collect(r.waitForArrival)
                timeAboard.collect(r.timeAboard)
                completions++
            }
        }

        private val tba = ExponentialRV(MEAN_INTERARRIVAL, streamNum = ARRIVAL_STREAM)
        private val generator = EntityGenerator(::Part, tba, tba)

        override fun initialize() {
            waitForArrival.reset()
            timeAboard.reset()
            completions = 0.0
        }
    }

    // ---- running them --------------------------------------------------------------------------

    private fun runPassive(): Pair<PassiveShop, Carry> {
        val m = Model("PerCarryPassive")
        val s = PassiveShop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = HORIZON
        m.simulate()
        return s to Carry(
            s.completions, s.system.approachTime, s.system.rideTime,
            s.system.transportBlockedTime, s.system.zonesTraversedPerTransport,
            s.system.routeLengthPerTransport
        )
    }

    private fun runActive(): Pair<ActiveShop, Carry> {
        val m = Model("PerCarryActive")
        val s = ActiveShop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = HORIZON
        m.simulate()
        return s to Carry(
            s.completions, s.agv.approachTime, s.agv.rideTime,
            s.agv.transportBlockedTime, s.agv.zonesTraversedPerTransport,
            s.agv.routeLengthPerTransport
        )
    }

    private fun mean(r: ResponseCIfc) = r.withinReplicationStatistic.weightedAverage
    private fun count(r: ResponseCIfc) = r.withinReplicationStatistic.count

    // ---- the tests -----------------------------------------------------------------------------

    @Test
    @DisplayName("an active model observes the five per-carry figures once per delivered load")
    fun theActiveParadigmFeedsThePerCarryStatistics() {
        val (shop, carry) = runActive()
        assertTrue(
            carry.completions >= MIN_COMPLETIONS,
            "only ${carry.completions} loads were delivered; too few for the check to mean anything"
        )
        // The regression guard proper. Each of the five is observed once per delivered load, so a
        // count that is zero -- or merely short -- says an observation is being dropped somewhere.
        for ((what, response) in carry.five) {
            assertEquals(
                carry.completions, count(response), 0.0,
                "$what was observed ${count(response)} times over ${carry.completions} delivered " +
                        "loads. A count of zero means nothing is feeding it at all."
            )
        }
        // One cart on a network it has to itself: there is nothing to contend for, so blocking is
        // exactly zero rather than merely small. An implementation that reported total blocked time
        // instead of this journey's share would not produce a zero here.
        assertEquals(
            0.0, mean(carry.blocked), 0.0,
            "a single cart cannot be blocked by anything, yet blocked time averaged ${mean(carry.blocked)}"
        )
        println(
            "  active per-carry: %.0f loads, empty %.4f, loaded %.4f, blocked %.4f, %.2f zones, %.2f long"
                .format(
                    carry.completions, mean(carry.emptyMove), mean(carry.loadedMove),
                    mean(carry.blocked), mean(carry.zones), mean(carry.distance)
                )
        )
        assertTrue(shop.completions > 0.0, "the active shop delivered nothing")
    }

    @Test
    @DisplayName("the two move times are the travel legs only, excluding the loading and unloading delays")
    fun theMoveTimesExcludeTheLoadingAndUnloadingDelays() {
        val (shop, carry) = runActive()
        // Every load pays both delays and both are constant, so the difference of the means is
        // exactly one delay. This is the assertion that a response fed with plausible rubbish, or
        // measured on the wrong side of an arrival, could not satisfy.
        assertEquals(
            LOADING, shop.waitForArrival.average - mean(carry.emptyMove), 1.0e-9,
            "waiting for the vehicle to arrive exceeds its empty run by " +
                    "${shop.waitForArrival.average - mean(carry.emptyMove)}, which should be exactly " +
                    "the loading delay $LOADING"
        )
        assertEquals(
            UNLOADING, shop.timeAboard.average - mean(carry.loadedMove), 1.0e-9,
            "the carry exceeds the loaded run by " +
                    "${shop.timeAboard.average - mean(carry.loadedMove)}, which should be exactly " +
                    "the unloading delay $UNLOADING"
        )
    }

    @Test
    @DisplayName("the two paradigms agree on what the guide path cost each load")
    fun thePerCarryFiguresAgreeAcrossParadigms() {
        val (_, passive) = runPassive()
        val (_, active) = runActive()

        val report = StringBuilder("\nPer-carry figures, one cart, the same shop modelled both ways\n")
        report.append("%-28s %14s %14s %12s\n".format("figure", "passive", "active", "rel.diff"))
        var worst = 0.0
        var worstName = ""
        for ((what, p) in passive.five) {
            val a = active.five.getValue(what)
            val pv = mean(p)
            val av = mean(a)
            val rel = if (abs(pv) > 1.0e-12) abs(av - pv) / abs(pv) else abs(av - pv)
            if (rel > worst) { worst = rel; worstName = what }
            report.append("%-28s %14.6f %14.6f %12.2e\n".format(what, pv, av, rel))
        }
        report.append("completions: passive ${passive.completions}, active ${active.completions}\n")
        println(report)

        assertEquals(
            passive.completions, active.completions, 0.0,
            "the two paradigms delivered different numbers of loads, so nothing else compares$report"
        )
        // The route a load travels is a property of the network, not of who decided to send the
        // cart, so this one is held to the digit rather than to a tolerance.
        for (what in listOf("routeLengthPerTransport", "zonesTraversedPerTransport")) {
            assertEquals(
                mean(passive.five.getValue(what)), mean(active.five.getValue(what)), 1.0e-9,
                "the paradigms disagree on $what, so they are not carrying loads over the same path$report"
            )
        }
        for ((what, p) in passive.five) {
            val av = mean(active.five.getValue(what))
            val pv = mean(p)
            val rel = if (abs(pv) > 1.0e-12) abs(av - pv) / abs(pv) else abs(av - pv)
            assertTrue(rel < 0.02, "'$what' differs by more than 2% between the paradigms$report")
        }
        println("  worst per-carry disagreement: $worstName at ${"%.2e".format(worst)}")
    }
}
