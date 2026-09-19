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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ksl.modeling.agent.AgentModel
import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.fleet.policies.ParkInPlaceDisposition
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.modeling.guidedpath.rules.StartOfZoneControl
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.StatisticIfc
import org.slf4j.LoggerFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The guide path against **mathematics that existed before it did**.
 *
 *  Every other check in this suite compares the subsystem with something: with itself under a
 *  different configuration, with the other paradigm, or with a model of the same shop built in
 *  another tool. All of those can be defeated by a single consistent error — a subsystem wrong the
 *  same way in both runs passes them all, and so does one that reproduces a reference
 *  implementation's mistake.
 *
 *  This one cannot be defeated that way. It arranges the guide path so that it *provably degenerates
 *  to a system with a closed-form answer*, and then checks the closed form. If the arithmetic here
 *  disagrees, one of the two is wrong, and it is not the queueing theory.
 *
 *  ## Case A — exactly M/D/1
 *
 *  A one-way loop with a pickup at `P` and a drop at `Q`, one hundred units each way at a constant
 *  velocity of ten, and **one** cart. With a single vehicle there is no contention, and the cart can
 *  only get back to the pickup by completing the loop, so every customer costs the same
 *  deterministic two hundred units of travel: ten to come, ten to carry. Arrivals are Poisson. That
 *  is an M/D/1 queue, exactly and not approximately.
 *
 *  The cart is parked at the **drop**, not the pickup, so that even the first customer pays a whole
 *  lap and the service time is deterministic from the first observation rather than from the second.
 *
 *  The case gives **two** assertions, which is worth more than one, because a failure says which
 *  half is wrong:
 *
 *  - the wait for a vehicle against Pollaczek–Khinchine, `Wq = rho*D / (2(1-rho))`;
 *  - the service itself against `D`, **exactly** — it is deterministic, so a confidence interval
 *    would be the wrong instrument.
 *
 *  Both paradigms are checked. They measure the same two quantities in different places: the passive
 *  one in its pool's queue and its per-transport responses, the active one in the wait decomposition
 *  its dispatcher makes possible. Agreeing with the same closed form from two different instruments
 *  is a stronger statement than either alone.
 *
 *  ## Case C — the saturation bound
 *
 *  A loop whose outbound leg is **one zone a hundred units long**, so a cart is inside it for ten
 *  units and nobody else may be, and every delivery crosses it exactly once. Deliveries per unit
 *  time therefore cannot exceed `1/R = 0.1`, whatever the fleet size, and must approach it once the
 *  fleet is large enough to keep it busy. Demand is saturated, so the bottleneck rather than the
 *  arrival stream is what binds.
 *
 *  The measured throughput is the free-flow rate `n/100` while the fleet is small and **exactly the
 *  bound** once it is not:
 *
 *  ```
 *  carts    1      2      4      8     12     16     24
 *  thru  .0100  .0200  .0400  .0800  .1000  .1000  .1000
 *  free  .0100  .0200  .0400  .0800  .1200  .1600  .2400
 *  ```
 *
 *  ## What is not here, and why
 *
 *  **Case B, the congestion-free limit**, would assert that a guide path with no possible contention
 *  reproduces a free-path model. `FreePathVersusGuidedPathTest` already establishes it and more
 *  strongly than a stochastic run could: it is fully deterministic, and the two models agree to the
 *  digit at fleets of one and two before diverging. Repeating it under randomness would weaken the
 *  evidence, not add to it.
 *
 *  **Case D, M/M/c**, is deliberately skipped. Making a multi-vehicle fleet behave as `c`
 *  independent exponential servers requires assumptions the guide path exists to violate — vehicles
 *  that never block, never queue for space, and are interchangeable regardless of where they stand.
 *  A test whose failures were all about the approximation would teach nothing about the subsystem.
 *
 *  ## Acceptance
 *
 *  Two conditions, and the second is the one usually left out: the analytic value must lie inside
 *  the 95% across-replication interval, **and** the half-width must be a small fraction of that
 *  value. An interval wide enough to admit anything passes the first test without being evidence of
 *  anything. Expectations are computed here from the arrival rate and the geometry, never pasted, so
 *  that changing the layout changes what is expected of it.
 */
class QueueingLimitsTest {

    private companion object {
        /** One hundred units out and one hundred back, at ten a unit: a lap costs twenty. */
        const val LEG: Double = 100.0
        const val VELOCITY: Double = 10.0

        /** The deterministic service of Case A: fetch the customer, then carry it. */
        const val D: Double = 2.0 * LEG / VELOCITY

        const val REPLICATIONS: Int = 30
        const val HORIZON: Double = 400_000.0
        const val WARM_UP: Double = 20_000.0

        /** How wide an interval may be and still be evidence, as a fraction of what it estimates. */
        const val MAX_HALF_WIDTH_FRACTION: Double = 0.03

        /** Pollaczek–Khinchine for deterministic service. */
        fun deterministicWait(rho: Double): Double = rho * D / (2.0 * (1.0 - rho))

        fun loop(name: String): GuidedPathNetwork = GuidedPathNetwork.builder(name)
            .link("Out", "P", "Q", length = LEG, zoneLength = 10.0, beginDirection = 0.0)
            .link("Back", "Q", "P", length = LEG, zoneLength = 10.0, beginDirection = 180.0)
            .build()
    }

    // ---- Case A, the passive paradigm ----------------------------------------------------------

    private class PassiveLoop(parent: ModelElement, meanTimeBetweenArrivals: Double) :
        ProcessModel(parent, "PassiveLoop") {

        val network: GuidedPathNetwork = loop("PassiveLoop")

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        // Parked at the drop, so the very first customer pays a whole lap like every other.
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("Q"), ConstantRV(VELOCITY), name = "Cart"
        )

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart), idleDispositionRule = ParkInPlaceRule(), name = "Carts"
        )

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("P")
                guidedTransport(carts, destination = "Q", pickupLocation = "P")
            }
        }

        private val tba = ExponentialRV(meanTimeBetweenArrivals, 1)
        private val generator = EntityGenerator(::Part, tba, tba)
    }

    // ---- Case A, the active paradigm -----------------------------------------------------------

    private class ActiveLoop(parent: ModelElement, meanTimeBetweenArrivals: Double) :
        ProcessModel(parent, "ActiveLoop") {

        val network: GuidedPathNetwork = loop("ActiveLoop")

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        val cart = AgvVehicle(
            agv, TransporterPlacement.At("Q"), ConstantRV(VELOCITY), name = "Cart"
        ).apply { dispositionPolicy = ParkInPlaceDisposition() }

        /** The dispatcher's own decomposition: waiting for a vehicle, and then being served by it. */
        val waitForVehicle = Response(this, "WaitForVehicle")
        val service = Response(this, "Service")

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("P")
                val r = transportByFleet(agv, destination = "Q", origin = "P")
                waitForVehicle.value = r.waitForAssignment
                service.value = r.waitForArrival + r.timeAboard
            }
        }

        private val tba = ExponentialRV(meanTimeBetweenArrivals, 1)
        private val generator = EntityGenerator(::Part, tba, tba)
    }

    // ---- Case C --------------------------------------------------------------------------------

    private class Bottleneck(
        parent: ModelElement,
        numCarts: Int,
        private val numLoads: Int
    ) : ProcessModel(parent, "Bottleneck") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Bottleneck")
            // One zone, one hundred long: a cart is inside it for ten units and nobody else may be.
            .link("Neck", "P", "M", length = 100.0, zoneLength = 100.0, beginDirection = 0.0)
            .link("Return", "M", "P", length = 900.0, zoneLength = 10.0, beginDirection = 180.0)
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val carts = (1..numCarts).map { i ->
            GuidedTransporter(
                system, TransporterPlacement.OnZone("Return.Zone${i * 3}"),
                ConstantRV(VELOCITY), 1, StartOfZoneControl(), "Cart$i"
            )
        }

        val pool = GuidedTransporterPoolWithQ(
            this, system, carts, idleDispositionRule = ParkInPlaceRule(), name = "Carts"
        )

        val delivered = Counter(this, "Delivered")

        private inner class Load : Entity() {
            val circulating = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("P")
                while (true) {
                    guidedTransport(pool, destination = "M", pickupLocation = "P")
                    delivered.increment()
                    // Returned to the pickup so the demand never runs out. An artifice to saturate
                    // the fleet, not a model of anything: what is being measured is the ceiling the
                    // bottleneck imposes, and a ceiling is only visible against unlimited demand.
                    currentLocation = network.requireLocation("P")
                }
            }
        }

        override fun initialize() {
            repeat(numLoads) { activate(Load().circulating) }
        }
    }

    // ---- acceptance ----------------------------------------------------------------------------

    /**
     *  The analytic value must lie inside the interval, and the interval must be narrow enough for
     *  that to mean something.
     */
    private fun assertAgrees(what: String, analytic: Double, measured: StatisticIfc) {
        val gap = abs(measured.average - analytic)
        assertTrue(
            gap <= measured.halfWidth,
            "$what: analytic %.4f is outside the 95%% interval %.4f +/- %.4f (out by %.4f)"
                .format(analytic, measured.average, measured.halfWidth, gap)
        )
        assertTrue(
            measured.halfWidth <= MAX_HALF_WIDTH_FRACTION * analytic,
            "$what: the interval is too wide to be evidence -- half-width %.4f is %.1f%% of the " +
                    "analytic %.4f, over the %.0f%% this test requires"
                .format(
                    measured.halfWidth, 100.0 * measured.halfWidth / analytic, analytic,
                    100.0 * MAX_HALF_WIDTH_FRACTION
                )
        )
    }

    /**
     *  Runs with the horizon diagnostics silenced and restores them afterwards.
     *
     *  A terminating run of a queue at 70% utilization ends with work outstanding -- that is what a
     *  queue is -- and the active subsystem says so, per replication, naming every task and entity
     *  it stranded. That is the right behaviour and the wrong volume here: sixty replications of it
     *  buries the four numbers this test exists to print. The passive paradigm is silent about the
     *  same condition, so leaving it on would also make the two halves look different when they are
     *  not.
     */
    private fun <T> withoutHorizonDiagnostics(block: () -> T): T {
        val logger = LoggerFactory.getLogger(AgentModel::class.java) as Logger
        val previous = logger.level
        logger.level = Level.ERROR
        try {
            return block()
        } finally {
            logger.level = previous
        }
    }

    private fun report(case: String, rho: Double, analytic: Double, measured: StatisticIfc) {
        println(
            "  %-28s rho=%.2f  analytic %9.4f   measured %9.4f +/- %.4f  (%.2f%% wide)"
                .format(case, rho, analytic, measured.average, measured.halfWidth,
                    100.0 * measured.halfWidth / analytic)
        )
    }

    // ---- Case A --------------------------------------------------------------------------------

    @Test
    @Tag("slow")
    @DisplayName("Case A: one cart on a one-way loop is exactly M/D/1, in both paradigms")
    fun oneCartOnALoopIsExactlyMD1() = withoutHorizonDiagnostics {
        println()
        println("Case A -- M/D/1, D = $D, $REPLICATIONS replications of ${HORIZON.toInt()}")
        for (rho in listOf(0.5, 0.7)) {
            val meanTba = D / rho          // lambda = rho / D
            val analytic = deterministicWait(rho)

            val passive = Model("MD1-passive-$rho").let { m ->
                val shop = PassiveLoop(m, meanTba)
                m.numberOfReplications = REPLICATIONS
                m.lengthOfReplication = HORIZON
                m.lengthOfReplicationWarmUp = WARM_UP
                m.simulate()
                shop
            }
            val passiveWait = passive.carts.waitingQ.timeInQ.acrossReplicationStatistic
            report("passive, wait for a cart", rho, analytic, passiveWait)
            assertAgrees("passive wait at rho=$rho", analytic, passiveWait)

            // Deterministic, so it is asserted as an identity rather than through an interval.
            val empty = passive.system.approachTime.acrossReplicationStatistic.average
            val loaded = passive.system.rideTime.acrossReplicationStatistic.average
            assertEquals(
                D, empty + loaded, 1.0e-9,
                "passive service at rho=$rho is not deterministic: empty $empty + loaded $loaded"
            )

            val active = Model("MD1-active-$rho").let { m ->
                val shop = ActiveLoop(m, meanTba)
                m.numberOfReplications = REPLICATIONS
                m.lengthOfReplication = HORIZON
                m.lengthOfReplicationWarmUp = WARM_UP
                m.simulate()
                shop
            }
            val activeWait = active.waitForVehicle.acrossReplicationStatistic
            report("active, wait for assignment", rho, analytic, activeWait)
            assertAgrees("active wait at rho=$rho", analytic, activeWait)

            assertEquals(
                D, active.service.acrossReplicationStatistic.average, 1.0e-9,
                "active service at rho=$rho is not deterministic"
            )
        }
        println()
    }

    // ---- Case C --------------------------------------------------------------------------------

    @Test
    @Tag("slow")
    @DisplayName("Case C: a single-zone bottleneck caps throughput at one over its ride time")
    fun aSingleZoneBottleneckCapsThroughput() {
        val rideTime = 100.0 / VELOCITY
        val bound = 1.0 / rideTime
        val horizon = 100_000.0
        val warmUp = 10_000.0

        println()
        println("Case C -- bottleneck ride time $rideTime, so throughput cannot exceed $bound")
        println("  %5s %12s %12s %12s".format("carts", "throughput", "free-flow", "% of bound"))

        var atCapacity = 0.0
        for (n in listOf(1, 2, 4, 8, 12, 16, 24)) {
            val m = Model("Saturation-$n")
            val shop = Bottleneck(m, n, numLoads = 40)
            m.numberOfReplications = 1
            m.lengthOfReplication = horizon
            m.lengthOfReplicationWarmUp = warmUp
            m.simulate()

            val throughput = shop.delivered.value / (horizon - warmUp)
            val freeFlow = n / 100.0
            println("  %5d %12.6f %12.6f %11.2f%%".format(n, throughput, freeFlow, 100.0 * throughput / bound))

            // The inequality holds for every fleet size, and needs no tolerance: it is arithmetic
            // about a zone that admits one vehicle at a time.
            assertTrue(
                throughput <= bound + 1.0e-9,
                "$n carts delivered $throughput per unit time, above the bottleneck's ceiling $bound"
            )
            // Below the bound the fleet, not the neck, is what limits: the carts never contend, so
            // throughput is exactly what free flow predicts.
            if (freeFlow < bound) {
                assertEquals(
                    freeFlow, throughput, 1.0e-9,
                    "$n carts are under the ceiling and should run at the free-flow rate"
                )
            }
            if (n == 24) atCapacity = throughput
        }
        println()

        assertTrue(
            abs(atCapacity - bound) <= 0.02 * bound,
            "a fleet well past the ceiling should reach it, but managed %.6f against %.6f"
                .format(atCapacity, bound)
        )
    }
}
