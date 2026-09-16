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

import ksl.utilities.distributions.fitting.mixture.MixtureModeler

/**
 *  Example 2: fit a mixture to the same data.
 *
 *  Three lines do the work. The modeler sorts the data, cuts it into groups, fits every family
 *  in the catalog to each group, assembles the best combination, and does that for each number
 *  of components you ask for.
 *
 *  Compare the reported score with the single-distribution score from Example 1 — smaller is
 *  better.
 *
 *  The data ships with the examples, in
 *  `KSLExamples/chapterFiles/Appendix-Distribution Fitting`. You can open it, plot it, or
 *  load it into the Distribution app; every run of every example sees the same numbers.
 */
fun main() {
    val data = receptionDeskData()

    // This is the whole thing. Everything else in this file is printing.
    val modeler = MixtureModeler(data)
    val results = modeler.fit(numComponentsRange = 1..6)

    println("Fitting a mixture to the reception desk data")
    println("--------------------------------------------")
    println(results)

    val best = results.best
    if (best == null) {
        println("No mixture could be fitted.")
        return
    }

    println("The recommended mixture, component by component")
    println("-----------------------------------------------")
    val mixture = best.candidate
    for (j in 0 until mixture.numComponents) {
        val weight = mixture.weights[j]
        val component = mixture.components[j]
        println("  component ${j + 1}:  weight = ${"%.4f".format(weight)}   ${component.name}")
    }
    println()
    println("score (${modeler.criterion.name}) = ${"%.3f".format(best.criterionValue.value)}")
    println("free parameters             = ${mixture.numFreeParameters}")
    println()

    // How each number of components scored. This is the table Example 3 explores properly.
    println("Best score at each number of components")
    println("---------------------------------------")
    for ((k, ranked) in results.bestByNumComponents().toSortedMap()) {
        val marker = if (k == mixture.numComponents) "  <-- recommended" else ""
        println("  k = $k:  ${"%.3f".format(ranked.criterionValue.value)}$marker")
    }
    println()

    // The point of all this: the fit converts to an ordinary KSL distribution.
    val fitted = best.distribution
    println("The fit as a KSL distribution")
    println("-----------------------------")
    println("  mean     = ${"%.4f".format(fitted.mean())}   (data mean ${"%.4f".format(data.average())})")
    println("  variance = ${"%.4f".format(fitted.variance())}")
    println("  median   = ${"%.4f".format(fitted.invCDF(0.5))}")
    println()
    println("  and it will generate values for a simulation:")
    val generated = fitted.randomVariable(streamNumber = 99).sample(5)
    println("  ${generated.joinToString(", ") { "%.3f".format(it) }}")
    println()
    println("Example 3 looks at how that choice of k is made, and how much to trust it.")
}
