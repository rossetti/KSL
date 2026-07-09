/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.controls.experiments

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ksl.controls.experiments.*
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * Demonstrates [ParallelDesignedExperiment] — the concurrent counterpart of
 * [DesignedExperiment] (see `DemoExperiments.kt` under `ksl.examples.book.appendixD`
 * for the sequential path). Each design point runs on its own fresh model,
 * dispatched across a bounded coroutine pool; with the default stream policy
 * the results reproduce exactly what a sequential run of the same design
 * would produce.
 *
 * Model: M/M/c queue ([GIGcQueue]), the same running example used throughout
 * this package's guide. Two factors are varied: the control
 * `"MM1Q.numServers"` and the random-variable parameter
 * `"<modelName>:ServiceTime.mean"`.
 *
 * Run `main` to execute all four demonstrations in turn.
 */
fun main() = runBlocking {
    demoParallelDesignedExperiment()
    demoStreamPolicyChoice()
    demoProgressAndCancellation()
    demoCustomDesignPoints()
}

/** A builder that returns a FRESH, independent model per call — required by [ParallelDesignedExperiment]. */
fun mm1ModelBuilder(modelName: String): ModelBuilderIfc = object : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val m = Model(modelName)
        m.numberOfReplications = 20
        m.lengthOfReplication = 1000.0
        m.lengthOfReplicationWarmUp = 200.0
        GIGcQueue(m, numServers = 1, name = "MM1Q")
        return m
    }
}

/**
 * Runs every point of a 2x2 two-level factorial design concurrently, then
 * reads results back with the same reporting surface [DesignedExperimentIfc]
 * defines — [ParallelDesignedExperiment] implements it, just like
 * [DesignedExperiment] does.
 */
suspend fun demoParallelDesignedExperiment() {
    val servers = TwoLevelFactor("Servers", 1.0, 2.0)
    val meanST = TwoLevelFactor("MeanST", 0.5, 0.8)
    val design = TwoLevelFactorialDesign(setOf(servers, meanST))

    val pde = ParallelDesignedExperiment(
        name = "Parallel DOE",
        modelBuilder = mm1ModelBuilder("MM1_Test"),
        factorSettings = mapOf<Factor, String>(
            servers to "MM1Q.numServers",
            meanST to "MM1_Test:ServiceTime.mean"
        ),
        design = design
    )
    pde.simulateAll(numRepsPerDesignPoint = 10)
    println("ran ${pde.numSimulationRuns} design points")

    // Design-point label -> per-replication observations (box-plot ready);
    // ParallelDesignedExperiment exposes the same extraction methods DesignedExperiment does.
    val obs: Map<String, DoubleArray> = pde.observationsAsMap("System Time")
    for ((label, values) in obs) {
        println("$label: n=${values.size} mean=${values.average()}")
    }
}

/**
 * Compares the two design-point random-stream policies. The default,
 * independent (non-overlapping) streams, reproduces the sequential
 * [DesignedExperiment] numbers exactly; common random numbers instead starts
 * every point at the same stream block, for paired comparisons across points.
 */
suspend fun demoStreamPolicyChoice() {
    val servers = TwoLevelFactor("Servers", 1.0, 2.0)
    val meanST = TwoLevelFactor("MeanST", 0.5, 0.8)
    val design = TwoLevelFactorialDesign(setOf(servers, meanST))
    val pde = ParallelDesignedExperiment(
        name = "Parallel DOE - Stream Policy",
        modelBuilder = mm1ModelBuilder("MM1_Test"),
        factorSettings = mapOf<Factor, String>(
            servers to "MM1Q.numServers",
            meanST to "MM1_Test:ServiceTime.mean"
        ),
        design = design
    )
    // Default: independent (non-overlapping) streams across points.
    pde.useIndependentRandomStreams(startingStreamAdvance = 0)
    // Or: common random numbers — every point starts at the same block.
    pde.useCommonRandomNumbers()
    pde.simulateAll(numRepsPerDesignPoint = 10)
    println("stream policy demo ran ${pde.numSimulationRuns} design points")
}

/**
 * Shows live per-point progress callbacks, and cancelling one point from a
 * concurrent coroutine while the run is still in flight. Cancellation races
 * against that point's own completion by design — a point already finished
 * when the cancel request arrives simply is not cancellable. `simulateAll` is
 * launched in its own coroutine specifically so `cancelDesignPoint` below runs
 * concurrently with it rather than after it.
 */
suspend fun demoProgressAndCancellation() = coroutineScope {
    val servers = TwoLevelFactor("Servers", 1.0, 2.0)
    val meanST = TwoLevelFactor("MeanST", 0.5, 0.8)
    val design = TwoLevelFactorialDesign(setOf(servers, meanST))
    val pde = ParallelDesignedExperiment(
        name = "Parallel DOE - Callbacks",
        modelBuilder = mm1ModelBuilder("MM1_Test"),
        factorSettings = mapOf<Factor, String>(
            servers to "MM1Q.numServers",
            meanST to "MM1_Test:ServiceTime.mean"
        ),
        design = design
    )
    launch {
        pde.simulateAll(
            numRepsPerDesignPoint = 5,
            onDesignPointStart = { dp -> println("start ${dp.number}") },
            onDesignPointComplete = { dp, snapshot ->
                println("done ${dp.number}: committed=${snapshot != null}")
            }
        )
    }
    // From another coroutine while the run is in flight:
    pde.cancelDesignPoint(1)
}

/**
 * Builds an arbitrary, hand-picked set of design points with
 * [ExperimentalDesign] rather than a systematic factorial grid — useful when
 * the points of interest do not form a regular lattice.
 */
fun demoCustomDesignPoints() {
    val a = Factor("A", doubleArrayOf(1.0, 5.0))
    val b = Factor("B", doubleArrayOf(1.0, 7.0))
    val design = ExperimentalDesign(setOf(a, b))
    design.addDesignPoint(doubleArrayOf(1.0, 1.0), numReps = 5)
    design.addDesignPoint(doubleArrayOf(5.0, 7.0), numReps = 5)
    // enforceRange = false permits points outside the factor level range.
    design.addDesignPoint(doubleArrayOf(9.0, 9.0), numReps = 5, enforceRange = false)
    println(design.designPointsAsDataframe())
}
