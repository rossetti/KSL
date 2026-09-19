package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.FurthestVehiclePolicy
import ksl.modeling.fleet.policies.LeastUsedVehiclePolicy
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.RandomAssignmentPolicy
import ksl.utilities.random.rng.RNStreamProvider
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.Statistic
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 *  The policies actually differ, and the differences have the sign the rules imply.
 *
 *  A substitutable seam whose alternatives all produce the same answer is not a seam -- it is a
 *  parameter nobody needs, and the way to find that out is to measure rather than to reason. Each
 *  comparison below is chosen so its expected direction follows from what the rule *is*, not from
 *  what happened to come out of a run:
 *
 *  - nearest beats furthest on waiting, because the empty leg is time the load spends waiting and
 *    furthest maximises it by construction;
 *  - batching costs waiting, because it delays every decision by its window on purpose;
 *  - least-used spreads work more evenly than nearest, because nearest concentrates on whichever
 *    vehicles are already near the active part of the layout.
 *
 *  Two carts on a one-way loop, run on common random numbers so that the arrival stream is
 *  identical across policies and the differences are attributable to the rules alone.
 */
class PolicyComparisonTest {

    companion object {
        const val REPLICATIONS = 8
        const val HORIZON = 4000.0
        const val ARRIVALS = 120
        const val MEAN_TBA = 55.0
    }

    class Result(
        val meanWait: Double,
        val completions: Double,
        /** Largest minus smallest per-vehicle completion count: how unevenly the work fell. */
        val imbalance: Double
    ) {
        override fun toString(): String =
            "wait=%.2f completions=%.1f imbalance=%.2f".format(meanWait, completions, imbalance)
    }

    private class Shop(parent: ModelElement, policy: AssignmentPolicyIfc) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")

        val carts = listOf(SimpleAgvNetwork.AGV1_HOME, SimpleAgvNetwork.AGV2_HOME)
            .mapIndexed { i, home ->
                AgvVehicle(agv, TransporterPlacement.At(home), ConstantRV(10.0), name = "Cart${i + 1}")
                    .apply { homeBase = home }
            }

        val waits = Statistic("waits")
        val perCart = Statistic("perCartSpread")

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val r = transportByFleet(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                )
                waits.collect(r.waitForAssignment + r.waitForArrival)
            }
        }

        private val tba = ExponentialRV(MEAN_TBA, streamNum = 1)

        inner class Source : Entity() {
            val g = process(isDefaultProcess = true) {
                repeat(ARRIVALS) { delay(tba); activate(Part().p) }
            }
        }

        override fun initialize() {
            waits.reset()
            activate(Source().g)
        }

        override fun replicationEnded() {
            super.replicationEnded()
            val counts = carts.map { it.numTasksCompleted.value }
            perCart.collect((counts.max() - counts.min()))
        }
    }

    private fun run(label: String, policy: AssignmentPolicyIfc): Result {
        val m = Model(label)
        val shop = Shop(m, policy)
        m.numberOfReplications = REPLICATIONS
        m.lengthOfReplication = HORIZON
        m.simulate()
        return Result(
            shop.waits.average,
            shop.agv.dispatcher.numTasksCompleted.acrossReplicationStatistic.average,
            shop.perCart.average
        )
    }

    @Test
    @DisplayName("The assignment rules differ, and each difference has the sign the rule implies")
    fun thePoliciesDifferInTheExpectedDirections() {
        val nearest = run("Nearest", NearestVehiclePolicy())
        val furthest = run("Furthest", FurthestVehiclePolicy())
        val leastUsed = run("LeastUsed", LeastUsedVehiclePolicy())
        val batched = run("Batched", BatchedAssignmentPolicy(45.0, NearestVehiclePolicy()))

        val report = buildString {
            append("\nPolicy comparison -- two carts, one-way loop, common random numbers\n")
            for ((n, r) in listOf(
                "NearestVehicle" to nearest, "FurthestVehicle" to furthest,
                "LeastUsedVehicle" to leastUsed, "Batched(45)" to batched
            )) append("  %-18s %s\n".format(n, r))
        }
        println(report)

        // The run has to do enough work for any of this to mean anything.
        assertTrue(nearest.completions > 30.0, "too little work for a comparison$report")

        // Nearest beats furthest on waiting. Furthest maximises the empty leg by construction, and
        // the empty leg is time the load spends waiting for its vehicle to arrive.
        assertTrue(nearest.meanWait < furthest.meanWait,
            "nearest-vehicle did not beat furthest-vehicle on waiting$report")

        // Batching costs waiting. It delays every decision by its window deliberately, so this is
        // the price side of the trade rather than a defect.
        assertTrue(batched.meanWait > nearest.meanWait,
            "a 45-unit batching window cost nothing, so it cannot be batching$report")

        // Least-used spreads the work more evenly than nearest. Nearest concentrates on whichever
        // vehicle is closest to the active part of the layout, which on a one-way loop is
        // persistently the same one.
        assertTrue(leastUsed.imbalance < nearest.imbalance,
            "least-used did not balance the fleet better than nearest$report")

        // And the rules are genuinely distinguishable: a seam whose alternatives agree is a
        // parameter nobody needs.
        val waits = listOf(nearest.meanWait, furthest.meanWait, leastUsed.meanWait, batched.meanWait)
        assertTrue(waits.distinct().size == waits.size, "two policies gave identical waits$report")
    }

    @Test
    @DisplayName("A randomised policy is reproducible, because its stream is the model's")
    fun aRandomisedPolicyIsReproducible() {
        // The stream is supplied rather than created inside the policy, which is what makes this
        // possible. A policy that reached for a global generator would give a different answer on
        // every run of the same model -- and would do so silently, since nothing about a single run
        // would look wrong.
        fun once(): Result {
            val provider = RNStreamProvider()
            return run("Random", RandomAssignmentPolicy(provider.rnStream(7)))
        }

        val first = once()
        val second = once()
        assertTrue(first.meanWait == second.meanWait && first.completions == second.completions,
            "the same policy on the same stream gave different answers: $first vs $second")

        // And it is genuinely randomising rather than degenerating to a fixed choice: with two
        // carts it should not perfectly balance the way least-used does, nor concentrate the way
        // nearest does.
        val nearest = run("NearestForRandomCheck", NearestVehiclePolicy())
        assertTrue(first.meanWait != nearest.meanWait,
            "the random policy gave exactly the nearest policy's answer, so it is not choosing " +
                    "at random: $first vs $nearest")
        assertTrue(first.completions > 30.0, "too little work for the check to mean anything: $first")
    }
}
