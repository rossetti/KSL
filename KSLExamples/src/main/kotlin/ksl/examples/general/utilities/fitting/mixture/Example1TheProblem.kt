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
import ksl.utilities.statistic.Statistic

/**
 *  Example 1: the problem that motivates everything else.
 *
 *  Reception desk service times come from three kinds of visitor: booked check-ins are quick,
 *  new registrations take longer, and a few complex cases take much longer still. The 400
 *  service times used here are a sample from exactly that. We do what the standard workflow
 *  does — fit a catalog of distributions and take the best.
 *
 *  Watch what happens. Every family fits badly, and the tool still recommends one.
 *
 *  Run this file, then run Example 2 to see what a mixture does with the same data.
 *
 *  The data ships with the examples, in
 *  `KSLExamples/chapterFiles/Appendix-Distribution Fitting`. You can open it, plot it, or
 *  load it into the Distribution app; every run of every example sees the same numbers.
 */
fun main() {
    val data = receptionDeskData()

    println("Reception desk service times (minutes)")
    println("--------------------------------------")
    val stats = Statistic(data)
    println("observations = ${stats.count.toInt()}")
    println("average      = ${"%.3f".format(stats.average)}")
    println("std. dev.    = ${"%.3f".format(stats.standardDeviation)}")
    println("minimum      = ${"%.3f".format(stats.min)}")
    println("maximum      = ${"%.3f".format(stats.max)}")
    println()

    // The histogram is the first thing to look at. There is clearly more than one bump.
    val modeler = PDFModeler(data)
    println(modeler.histogram)
    println()
    modeler.histogram.histogramPlot().showInBrowser("Reception desk service times")

    // Now the standard workflow: fit every family in the catalog, score them, take the best.
    val results = modeler.estimateAndEvaluateScores()
    println("What the standard single-distribution workflow recommends")
    println("---------------------------------------------------------")
    results.resultsSortedByScoring.forEach(::println)

    val top = results.resultsSortedByScoring.first()
    println()
    println("** Recommended: ${top.name} **")
    println()
    top.distributionFitPlot().showInBrowser("Best single distribution: ${top.name}")

    println("Look at the fit plot. The recommendation is the best of a bad set:")
    println("no single distribution in the catalog has this shape, and the tool")
    println("has no way to say so other than through its scores.")
    println()
    println("Example 2 fits a mixture to the same data.")
}
