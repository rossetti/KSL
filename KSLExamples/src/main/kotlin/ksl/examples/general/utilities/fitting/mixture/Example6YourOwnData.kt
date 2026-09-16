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
import ksl.utilities.distributions.fitting.mixture.MixtureModeler
import ksl.utilities.io.KSLFileUtil

/**
 *  Example 6: fitting a mixture to your own data.
 *
 *  Choose a text file of numbers, one per line. The example splits it in two, fits a mixture to
 *  the first half, and checks it against the second.
 *
 *  For a first run, point it at one of the datasets in
 *  `KSLExamples/chapterFiles/Appendix-Distribution Fitting` — the reception desk service times
 *  the other examples use, or any of the book's fitting datasets beside them.
 *
 *  On real data there is no truth to compare against, so the checks are different from Example 5:
 *
 *  - Does the mixture beat the best single distribution?
 *  - Does it account for data it was not fitted to?
 *  - Does the picture look right?
 *
 *  Those are weaker than knowing the answer, and it is worth being honest that they are.
 */
fun main() {
    val file = KSLFileUtil.chooseFile()
    if (file == null) {
        println("No file chosen.")
        return
    }
    val all = KSLFileUtil.scanToArray(file.toPath())
    if (all.size < 40) {
        println("Only ${all.size} observations. Fit at least 40 or the split leaves too little.")
        return
    }

    // Split in two. The first half fits, the second half checks.
    val half = all.size / 2
    val fitting = all.copyOfRange(0, half)
    val checking = all.copyOfRange(half, all.size)

    println("Fitting ${fitting.size} observations, holding back ${checking.size}")
    println("=".repeat(60))
    println()

    // The bar to beat: the best single distribution.
    val single = PDFModeler(fitting).estimateAndEvaluateScores().resultsSortedByScoring.first()
    println("Best single distribution: ${single.name}")
    println()

    val results = MixtureModeler(fitting).fit(numComponentsRange = 1..6)
    println(results)

    val best = results.best
    if (best == null) {
        println("No mixture could be fitted. Common causes: too few observations, or many")
        println("repeated values, which limits where cuts may fall.")
        return
    }
    val mixture = best.candidate

    println("Recommended mixture")
    println("-------------------")
    for (j in 0 until mixture.numComponents) {
        println("  weight = ${"%.4f".format(mixture.weights[j])}   ${mixture.components[j].name}")
    }
    println()
    println("Score at each number of components (smaller is better)")
    for ((k, ranked) in results.bestByNumComponents().toSortedMap()) {
        println("  k = $k:  ${"%.3f".format(ranked.criterionValue.value)}")
    }
    println()

    // Does it account for the data it never saw? This is the strongest check available when
    // there is no truth to compare against.
    val coverage = results.coverage(checking)!!
    println(coverage)
    val share = coverage.uncoveredShare
    println()

    println("Every criterion's view of the number of components")
    println("--------------------------------------------------")
    println(results.criterionSummary())
    println()

    if (share > 0.0) {
        println("Some held-back values fall where the fitted mixture has no density. If that")
        println("share is more than a percent or two, be careful: the mixture may be describing")
        println("this sample rather than the process that produced it.")
        println()
    }

    val fitted = best.distribution
    println("Using the fit")
    println("-------------")
    println("  mean   = ${"%.4f".format(fitted.mean())}")
    println("  median = ${"%.4f".format(fitted.invCDF(0.5))}")
    println("  five generated values: " +
            fitted.randomVariable(streamNumber = 99).sample(5).joinToString(", ") { "%.3f".format(it) })
    println()
    println("  That object is an ordinary KSL distribution. Hand it to a RandomVariable and a")
    println("  simulation model can use it directly.")
    println()

    println("Before you trust this")
    println("---------------------")
    println("1. Plot it. A number that looks fine can still be the wrong shape.")
    println("2. Re-run on a different split. If the recommended k moves, it was not well")
    println("   determined by the data.")
    println("3. Ask whether the components mean anything. If the fit says four components and")
    println("   you can only name two mechanisms, the extra two are probably describing noise.")
    println("4. Remember where this method is strong and where it is not: the fitted density")
    println("   is usually good, while the component count and families usually are not. Use")
    println("   the mixture to generate values, and be cautious about interpreting its parts.")
}
