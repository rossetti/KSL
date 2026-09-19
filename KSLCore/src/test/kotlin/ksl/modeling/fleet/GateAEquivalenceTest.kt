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
 *  Gate A: the same shop, modelled both ways, must give the same answer.
 *
 *  This is the design's load-bearing risk and the reason it is tested before anything is built on
 *  top. The active paradigm moves the decision out of the entity's process and into a dispatcher
 *  with a process of its own, which changes *when* things are decided and therefore what order
 *  events occur in. If that changed the answers, the subsystem would not be a second way of
 *  modelling the same world -- it would be a different world, and every comparison a researcher
 *  wanted to make between paradigms would be confounded.
 *
 *  The two models are matched as tightly as the two APIs allow: one network, one cart, the same
 *  arrival stream, the same velocities and delays, and a matched pair of dispatching rules. The
 *  passive pool runs `ClosestByNetworkDistanceRule` and the active system its default
 *  `NearestVehiclePolicy`, which are the same rule stated in the two paradigms' own terms; with a
 *  single vehicle they are additionally degenerate, since there is only ever one candidate. Any
 *  difference that survives is therefore a difference of paradigm rather than of dispatching.
 *
 *  Acceptance is **statistical equivalence**. The exact comparison is computed and reported
 *  alongside, because a single mismatched digit localizes a fault that a confidence interval
 *  absorbs, and a run that once matched exactly and later does not is a regression signal even
 *  while the gate still passes.
 */
class GateAEquivalenceTest {

    companion object {
        const val REPLICATIONS = 20
        const val HORIZON = 3000.0
        const val ARRIVAL_STREAM = 1
        const val NUM_ARRIVALS = 200
        const val MEAN_INTERARRIVAL = 40.0
        const val VELOCITY = 10.0
        const val LOADING = 2.0
        const val UNLOADING = 3.0
    }

    /** What both models report, under names that mean the same thing in each. */
    class Outcome(
        val completions: Double,
        val meanTotalTime: Double,
        val meanRouteLength: Double,
        val fracTimeTransporting: Double,
        val fracTimeMovingEmpty: Double,
        val fracTimeBlocked: Double
    ) {
        fun asMap() = mapOf(
            "completions" to completions,
            "meanTotalTime" to meanTotalTime,
            "meanRouteLength" to meanRouteLength,
            "fracTimeTransporting" to fracTimeTransporting,
            "fracTimeMovingEmpty" to fracTimeMovingEmpty,
            "fracTimeBlocked" to fracTimeBlocked
        )
    }

    /** The passive shop: the entity claims a cart, steers it, and releases it. */
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

        val totalTimes = Statistic("passiveTotal")
        val routeLengths = Statistic("passiveRoute")
        var completions = 0.0

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val started = time
                val r = guidedTransport(
                    pool, SimpleAgvNetwork.EXIT_STATION,
                    pickupLocation = SimpleAgvNetwork.ENTRY_STATION,
                    loadingDelay = ConstantRV(LOADING), unLoadingDelay = ConstantRV(UNLOADING)
                )
                totalTimes.collect(time - started)
                routeLengths.collect(r.routeLength)
                completions++
            }
        }

        private val tba = ExponentialRV(MEAN_INTERARRIVAL, streamNum = ARRIVAL_STREAM)

        inner class Source : Entity() {
            val g = process(isDefaultProcess = true) {
                repeat(NUM_ARRIVALS) { delay(tba); activate(Part().p) }
            }
        }

        override fun initialize() {
            totalTimes.reset(); routeLengths.reset(); completions = 0.0
            activate(Source().g)
        }
    }

    /** The active shop: the entity asks and suspends; a dispatcher and a vehicle do the rest. */
    private class ActiveShop(parent: ModelElement) : ProcessModel(parent, "Active") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(VELOCITY), name = "Cart"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        val totalTimes = Statistic("activeTotal")
        val routeLengths = Statistic("activeRoute")
        var completions = 0.0

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val started = time
                val r = transportByFleet(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION,
                    loadingDelay = ConstantRV(LOADING), unLoadingDelay = ConstantRV(UNLOADING)
                )
                totalTimes.collect(time - started)
                routeLengths.collect(r.routeLength)
                completions++
            }
        }

        private val tba = ExponentialRV(MEAN_INTERARRIVAL, streamNum = ARRIVAL_STREAM)

        inner class Source : Entity() {
            val g = process(isDefaultProcess = true) {
                repeat(NUM_ARRIVALS) { delay(tba); activate(Part().p) }
            }
        }

        override fun initialize() {
            totalTimes.reset(); routeLengths.reset(); completions = 0.0
            activate(Source().g)
        }
    }

    private fun runPassive(): Outcome {
        val m = Model("GateAPassive")
        val s = PassiveShop(m)
        m.numberOfReplications = REPLICATIONS
        m.lengthOfReplication = HORIZON
        m.simulate()
        return Outcome(
            s.completions, s.totalTimes.average, s.routeLengths.average,
            s.cart.fracTimeTransporting.withinReplicationStatistic.weightedAverage,
            s.cart.fracTimeMovingEmpty.withinReplicationStatistic.weightedAverage,
            s.cart.fracTimeBlocked.withinReplicationStatistic.weightedAverage
        )
    }

    private fun runActive(): Outcome {
        val m = Model("GateAActive")
        val s = ActiveShop(m)
        m.numberOfReplications = REPLICATIONS
        m.lengthOfReplication = HORIZON
        m.simulate()
        return Outcome(
            s.completions, s.totalTimes.average, s.routeLengths.average,
            s.cart.fracTimeTransporting.withinReplicationStatistic.weightedAverage,
            s.cart.fracTimeMovingEmpty.withinReplicationStatistic.weightedAverage,
            s.cart.fracTimeBlocked.withinReplicationStatistic.weightedAverage
        )
    }

    @Test
    @DisplayName("Gate A: the passive and active models of one shop agree")
    fun theTwoParadigmsAgree() {
        val passive = runPassive().asMap()
        val active = runActive().asMap()

        val report = StringBuilder("\nGate A -- passive versus active, last replication\n")
        report.append("%-24s %14s %14s %12s\n".format("statistic", "passive", "active", "rel.diff"))
        var worst = 0.0
        var worstName = ""
        for ((k, p) in passive) {
            val a = active[k]!!
            val rel = if (abs(p) > 1e-12) abs(a - p) / abs(p) else abs(a - p)
            if (rel > worst) { worst = rel; worstName = k }
            report.append("%-24s %14.6f %14.6f %12.2e\n".format(k, p, a, rel))
        }
        val exact = passive.all { (k, v) -> active[k] == v }
        report.append("exact agreement: $exact\n")
        println(report)

        // The route a load travels is a property of the network, not of who decided to send the
        // cart. If these differ, the two models are not carrying loads over the same path and no
        // comparison of times means anything.
        assertEquals(passive["meanRouteLength"]!!, active["meanRouteLength"]!!, 1e-9,
            "the two paradigms routed loads differently$report")

        // The gate itself. Equivalence to within a tolerance that a difference of event ordering
        // could plausibly produce, but that a difference of behaviour could not.
        for ((k, p) in passive) {
            val a = active[k]!!
            val rel = if (abs(p) > 1e-12) abs(a - p) / abs(p) else abs(a - p)
            assertTrue(rel < 0.02, "'$k' differs by more than 2%$report")
        }

        assertTrue(passive["completions"]!! > 20.0, "too little work for the gate to mean anything$report")
        // Exact agreement is reported, not required: see the class comment.
        println(if (exact) "Gate A: exact agreement" else "Gate A: statistical agreement; worst = $worstName at ${"%.2e".format(worst)}")
    }
}
