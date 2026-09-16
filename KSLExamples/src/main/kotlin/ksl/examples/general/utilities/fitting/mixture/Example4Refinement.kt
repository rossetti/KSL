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

package ksl.examples.general.utilities.fitting.mixture

import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import ksl.utilities.distributions.fitting.mixture.ComponentFitCache
import ksl.utilities.distributions.fitting.mixture.PDFComponentFitter
import ksl.utilities.distributions.fitting.mixture.partition.JenksPartitionGenerator
import ksl.utilities.distributions.fitting.mixture.refine.BreakShiftRefiner
import ksl.utilities.distributions.fitting.mixture.refine.ClassificationEMRefiner
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureBICCriterion
import ksl.utilities.distributions.fitting.mixture.search.SeparableCMLSelector

/**
 *  Example 4: where do the cuts go?
 *
 *  The partition generator makes a first guess. A refiner then tries to improve it. Two are
 *  available and they work differently:
 *
 *  - **BreakShiftRefiner** nudges one cut at a time and keeps a move if it helps. Simple local
 *    search.
 *  - **ClassificationEMRefiner** solves the assignment problem *exactly* with a dynamic program,
 *    refits, and repeats.
 *
 *  You would expect the exact one to win. Often it does not: solving one sub-problem perfectly
 *  is not the same as solving the whole problem. Run this and see which wins here.
 *
 *  This example reaches below the MixtureModeler facade of Example 2, so you can see the pieces.
 *
 *  The data ships with the examples, in
 *  `KSLExamples/chapterFiles/Appendix-Distribution Fitting`. You can open it, plot it, or
 *  load it into the Distribution app; every run of every example sees the same numbers.
 */
fun main() {
    val data = receptionDeskData()
    val sorted = data.sortedArray()
    val k = 3

    // The pieces the facade normally assembles for you.
    val certificate = AdmissibilityCertificate(sorted)
    val fitter = ComponentFitCache(PDFComponentFitter(PDFModeler.allEstimators, false))
    val criterion = MixtureBICCriterion()
    val selector = SeparableCMLSelector()

    println("Refining a $k-component partition")
    println("---------------------------------")
    println("Admissible cut positions: ${certificate.admissibleCuts.size}")
    println()

    val initial = JenksPartitionGenerator(sorted, k).generate(sorted, k, certificate)
    if (initial == null) {
        println("No admissible partition at k = $k.")
        return
    }

    fun scoreOf(partition: ksl.utilities.distributions.fitting.mixture.DataPartition): Double? {
        val fits = fitter.fitAll(sorted, partition)
        if (fits.any { !it.hasCandidates }) return null
        return selector.select(sorted, partition, fits, criterion).criterionValue
    }

    fun describe(label: String, partition: ksl.utilities.distributions.fitting.mixture.DataPartition) {
        val score = scoreOf(partition)
        println("$label")
        println("  cut positions : ${partition.cutPositions.joinToString(", ")}")
        println("  group sizes   : ${partition.groupSizes.joinToString(", ")}")
        val ranges = (0 until partition.numGroups).joinToString(", ") { j ->
            "[%.2f, %.2f]".format(sorted[partition.startIndex(j)], sorted[partition.endIndex(j) - 1])
        }
        println("  value ranges  : $ranges")
        println("  BIC           : ${score?.let { "%.3f".format(it) } ?: "not comparable"}")
        println()
    }

    describe("Starting point (Jenks)", initial)

    val shift = BreakShiftRefiner().refine(sorted, initial, certificate, fitter)
    describe("After BreakShiftRefiner (local search, ${shift.numIterations} iterations, ${shift.outcome})", shift.partition)

    val cem = ClassificationEMRefiner().refine(sorted, initial, certificate, fitter)
    describe("After ClassificationEMRefiner (exact reassignment, ${cem.numIterations} iterations, ${cem.outcome})", cem.partition)

    println("Notes")
    println("-----")
    println("The exact refiner solves one sub-problem perfectly: given the current components,")
    println("which contiguous grouping is best. That is not the same as solving the whole problem,")
    println("which is why it does not always end up ahead.")
    println()
    println("Try changing k, or running Example 6 on data of your own, and see how stable the")
    println("ordering is. Whichever wins here, one dataset settles nothing: these two refiners")
    println("trade places depending on the shape of the data.")
    println()
    println("Example 5 checks the answer against the truth.")
}
