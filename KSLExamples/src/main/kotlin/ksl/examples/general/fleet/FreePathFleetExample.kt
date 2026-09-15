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

package ksl.examples.general.fleet

import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.FreePathFleet
import ksl.modeling.fleet.FreePathVehicle
import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.ConsolidatingPolicy
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.controls.experiments.ScenarioRunner
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.MultipleComparisonAnalyzer

/**
 *  A dispatcher, tours and multi-load consolidation over a **plain spatial model** -- no guide path,
 *  no zones, nothing that blocks.
 *
 *  This is the example for the cell most people fall into and least often see written down: they
 *  want a fleet that decides for itself, and their vehicles do not contend for space. A fork-lift
 *  yard, a hospital porter pool, a field-service crew: the vehicles queue for *work*, never for
 *  *aisles*.
 *
 *  ## What is substrate and what is not
 *
 *  Two lines in this file name a substrate:
 *
 *  ```
 *  val fleet = FreePathFleet(this, plane, places, ...)
 *  val cart  = FreePathVehicle(fleet, "Depot", ConstantRV(30.0), ...)
 *  ```
 *
 *  Everything else -- the batching window, the consolidating policy, the load capacity, the
 *  `transportByFleet` call in the part's process, every statistic printed below -- is the fleet
 *  layer and is written exactly as it would be over a guide path. Swapping the two lines above for
 *  `AgvSystem`/`AgvVehicle` and a network is the whole of what it takes to run this study on a
 *  guide path instead. That is what the movement seam bought.
 *
 *  ## What a free path assumes, reported rather than remembered
 *
 *  A free-path vehicle travels straight to where it is going at its own speed and never waits for
 *  another vehicle. That is an assumption, not a fact about a yard, and it is the assumption that
 *  makes free-path fleet-sizing optimistic: buy enough carts and the model will keep rewarding you,
 *  because nothing in it can represent the door they have to queue at.
 *
 *  So `FracTimeBlocked` is registered here and reads **exactly zero for the whole run**. It is not
 *  an oversight that it is present and flat; it is the model's central assumption made visible in
 *  the same row a guide-path run would fill in. If blocking matters to your answer, that row is
 *  telling you this substrate cannot produce it, and [ksl.examples.general.agv] is where to go.
 *
 *  ## What the run shows
 *
 *  The same yard is run twice, differing in one number: how many pallets a cart can hold. Whether
 *  consolidation pays is a property of how loaded the fleet is rather than a law, so the run
 *  reports it rather than the comment asserting it.
 *
 *  Read the throughput row of each pair first. At window 25 the two capacities deliver
 *  indistinguishable loads, so the time-in-system difference beside it is a comparison of two
 *  fleets doing the same work, and carrying up to four is worth having. At window 40 the
 *  capacity-1 fleet delivers detectably fewer: it has fallen behind, so its time in system is a
 *  number about the loads it managed rather than about the fleet, and comparing the times would be
 *  comparing two different questions. **Check throughput parity before believing a time-in-system
 *  comparison.** The paired difference is what makes "indistinguishable" and "detectably fewer"
 *  statements about the run rather than about the reader's eye.
 *
 *  The replication count is part of that. At ten replications the window-40 throughput deficit was
 *  about eight loads with a half-width of nine: the point estimate said the fleet had fallen behind
 *  and the interval could not tell it from noise, which is a reason to run more replications rather
 *  than to believe the point estimate. Forty resolves it.
 */
class FreePathFleetExample(
    parent: ModelElement,
    cartCapacity: Int,
    name: String,
    private val meanTimeBetweenArrivals: Double = 20.0,
    private val batchWindow: Double = 20.0,
    numCarts: Int = 2
) : ProcessModel(parent, name) {

    private val plane = Euclidean2DPlane()

    // A fleet is written in named places; the spatial model supplies the geometry between them.
    private val places = listOf(
        plane.Point(0.0, 0.0, "Depot"),
        plane.Point(300.0, 0.0, "Press"),
        plane.Point(300.0, 200.0, "Paint"),
        plane.Point(0.0, 200.0, "Ship")
    )

    init {
        spatialModel = plane
    }

    /**
     *  A batching window collects the tasks; the consolidating policy is what fills a cart that
     *  still has room. Both are ordinary fleet-layer policies and neither knows what it is running
     *  over. With a capacity of one the consolidating policy has nothing to consolidate and the
     *  inner rule decides everything, which is why the capacity-one run is a fair baseline rather
     *  than a differently-configured model.
     */
    val fleet = FreePathFleet(
        this, plane, places,
        assignmentPolicy = BatchedAssignmentPolicy(window = batchWindow, inner = ConsolidatingPolicy(NearestVehiclePolicy())),
        name = "Yard"
    )

    val carts = List(numCarts) { i ->
        FreePathVehicle(
            fleet, "Depot", ConstantRV(30.0), name = "Cart${i + 1}",
            loadCapacity = cartCapacity, stepSize = 10.0
        ).apply { homeBase = "Depot" }
    }

    // Named without the model's own name in front, so that every cell of the study reports the
    // same response and the cells can be paired replication by replication.
    private val myTimeInSystem = Response(this, TIME_IN_SYSTEM)
    val timeInSystem: ResponseCIfc
        get() = myTimeInSystem

    private val myDelivered = Counter(this, DELIVERED)
    val delivered: CounterCIfc
        get() = myDelivered

    private val myTimeBetweenArrivals = RandomVariable(
        this, ExponentialRV(meanTimeBetweenArrivals, streamNum = 1), name = "TBA"
    )
    val timeBetweenArrivalsRV: RandomVariableCIfc
        get() = myTimeBetweenArrivals

    inner class Pallet : Entity() {
        val movement = process(isDefaultProcess = true) {
            val arrived = time
            currentLocation = fleet.space.requireLocation("Press")
            // States what it needs and suspends. It never chooses a cart.
            transportByFleet(fleet, destination = "Ship", origin = "Press")
            myTimeInSystem.value = time - arrived
            myDelivered.increment()
        }
    }

    inner class Source : Entity() {
        val arrivals = process(isDefaultProcess = true) {
            repeat(600) {
                delay(myTimeBetweenArrivals)
                activate(Pallet().movement)
            }
        }
    }

    override fun initialize() {
        activate(Source().arrivals)
    }

    companion object {
        const val TIME_IN_SYSTEM: String = "TimeInSystem"
        const val DELIVERED: String = "Delivered"
        const val REPLICATIONS: Int = 40
        const val HORIZON: Double = 8000.0
        const val WARM_UP: Double = 1000.0

        /** The four cells of the study: two cart capacities against two batching windows. */
        fun cells(): List<Triple<String, Int, Double>> = listOf(
            Triple("Capacity1Window25", 1, 25.0),
            Triple("Capacity4Window25", 4, 25.0),
            Triple("Capacity1Window40", 1, 40.0),
            Triple("Capacity4Window40", 4, 40.0)
        )

        /**
         *  One scenario per cell. Load capacity is a property of the vehicles and the batching
         *  window a property of the policy, so both are fixed when the model is built: this is a
         *  runner over model instances rather than over control values.
         */
        fun buildRunner(): ScenarioRunner {
            val runner = ScenarioRunner("FreePathFleetYard")
            for ((label, capacity, window) in cells()) {
                val m = Model("FreePathYard_$label")
                FreePathFleetExample(m, capacity, label, batchWindow = window)
                runner.addScenario(
                    model = m, name = label, inputs = emptyMap(),
                    numberReplications = REPLICATIONS, lengthOfReplication = HORIZON,
                    lengthOfReplicationWarmUp = WARM_UP
                )
            }
            return runner
        }
    }
}

fun main() {
    val runner = FreePathFleetExample.buildRunner()
    runner.simulate()
    // Full half-width summary reports for all four cells go to the KSL output file; the console
    // gets the comparison.
    runner.write()

    println()
    println("A dispatcher over a plane -- no guide path, nothing that blocks")
    println("Two carts, pallets from Press to Ship, ${FreePathFleetExample.REPLICATIONS} replications " +
        "of ${FreePathFleetExample.HORIZON.toInt()} after a ${FreePathFleetExample.WARM_UP.toInt()} warm-up")
    println()
    println("  %-20s %11s %8s %11s %8s %9s %11s".format(
        "", "delivered", "hw", "in system", "hw", "blocked", "loads/move"))
    for ((label, _, _) in FreePathFleetExample.cells()) {
        val run = checkNotNull(runner.scenarioByName(label)?.simulationRun) { "$label did not run" }
        val stats = run.acrossReplicationStatistics()
        val d = checkNotNull(stats[FreePathFleetExample.DELIVERED]) { "no delivered response" }
        val t = checkNotNull(stats[FreePathFleetExample.TIME_IN_SYSTEM]) { "no time in system" }
        val b = checkNotNull(stats["Cart1:Body:FracTimeBlocked"]) { "no blocked response" }
        val loads = stats["Cart1:Body:LoadsPerLoadedMove"]
        println("  %-20s %11.1f %8.1f %11.2f %8.2f %9.4f %11s".format(
            label, d.average, d.halfWidth, t.average, t.halfWidth, b.average,
            loads?.let { "%.3f".format(it.average) } ?: "--"))
    }

    // Within a window, capacity 1 against capacity 4, paired replication by replication.
    for (window in listOf(25, 40)) {
        val one = "Capacity1Window$window"
        val four = "Capacity4Window$window"
        println()
        println("Window $window: $one minus $four, paired by replication (95% intervals)")
        println()
        println("  %-16s %13s %13s %13s".format("response", "difference", "half-width", "detectable?"))
        for (response in listOf(FreePathFleetExample.DELIVERED, FreePathFleetExample.TIME_IN_SYSTEM)) {
            val observations = runner.observationsAsMap(response).filterKeys { it == one || it == four }
            check(observations.size == 2) {
                "expected $response for both $one and $four, got ${observations.keys}"
            }
            val mca = MultipleComparisonAnalyzer(observations, response)
            val d = checkNotNull(mca.pairedDifferenceStatistic(one, four)) { "no pair for $response" }
            val detectable = if (kotlin.math.abs(d.average) > d.halfWidth) "yes" else "no"
            println("  %-16s %13.3f %13.3f %13s".format(response, d.average, d.halfWidth, detectable))
        }
    }

    println()
    println("  Full half-width summary reports for all four cells: ${KSL.outDir}")
}
