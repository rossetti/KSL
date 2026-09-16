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
import ksl.utilities.distributions.fitting.mixture.RecoveryMeasures

/**
 *  Example 5: how good is the answer, really?
 *
 *  Because the data were simulated we know the truth, so we can ask two quite different
 *  questions and get two quite different answers:
 *
 *  1. **Is the fitted density close to the true density?** Usually yes.
 *  2. **Did it identify the right components?** Usually not.
 *
 *  That distinction decides what the method is good for. For input modelling — generating
 *  service times that behave like the real ones — question 1 is what matters. For claiming
 *  "there are three kinds of visitor and here is what each looks like", question 2 is what
 *  matters, and the method is much weaker there.
 *
 *  The data ships with the examples, in
 *  `KSLExamples/chapterFiles/Appendix-Distribution Fitting`. You can open it, plot it, or
 *  load it into the Distribution app; every run of every example sees the same numbers.
 */
fun main() {
    val data = receptionDeskData()
    val holdOut = receptionDeskHoldOut()

    val results = MixtureModeler(data).fit(numComponentsRange = 1..6)
    val best = results.best
    if (best == null) {
        println("No mixture could be fitted.")
        return
    }
    val mixture = best.candidate

    println("Question 1: did it find the right number of components?")
    println("-------------------------------------------------------")
    println("  true       : ${receptionDeskComponents.size}")
    println("  recommended: ${mixture.numComponents}")
    println()

    println("Question 2: did it find the right families?")
    println("-------------------------------------------")
    println("  %-4s %-14s %-14s %8s %8s".format("j", "true family", "fitted family", "true w", "fit w"))
    val most = maxOf(receptionDeskComponents.size, mixture.numComponents)
    for (j in 0 until most) {
        val trueFamily = receptionDeskFamilies.getOrNull(j)?.toString() ?: "—"
        val fitFamily = mixture.components.getOrNull(j)?.rvType?.toString() ?: "—"
        val trueW = receptionDeskWeights.getOrNull(j)?.let { "%8.3f".format(it) } ?: "       —"
        val fitW = mixture.weights.getOrNull(j)?.let { "%8.3f".format(it) } ?: "       —"
        println("  %-4d %-14s %-14s %s %s".format(j + 1, trueFamily, fitFamily, trueW, fitW))
    }
    println()
    println("  (components are listed in order along the number line, so row j compares")
    println("   the j-th true component with the j-th fitted one)")
    println()

    println("Question 3: how close is the density?")
    println("-------------------------------------")
    val recovery = RecoveryMeasures.compare(
        receptionDeskWeights, receptionDeskComponents, receptionDeskFamilies,
        mixture.weights, mixture.distributions, mixture.components.map { it.rvType }
    )
    println("  Hellinger distance  = ${"%.5f".format(recovery.hellinger)}   (0 = identical, 1 = disjoint)")
    println("  L1 distance         = ${"%.5f".format(recovery.l1Distance)}")
    println("  Kolmogorov distance = ${"%.5f".format(recovery.kolmogorov)}")
    println()

    // Both truth and fit are ordinary distributions, so they compare directly.
    val fitted = best.distribution
    println("  true mean     = ${"%.4f".format(receptionDeskTruth.mean())}" +
            "     fitted mean     = ${"%.4f".format(fitted.mean())}")
    println("  true variance = ${"%.4f".format(receptionDeskTruth.variance())}" +
            "     fitted variance = ${"%.4f".format(fitted.variance())}")
    println()

    println("Question 4: does it handle data it has not seen?")
    println("------------------------------------------------")
    val evaluation = mixture.logLikelihood(holdOut)
    println("  held-out observations       = ${holdOut.size}")
    println("  given zero density by the fit = ${evaluation.numZeroDensity}")
    if (evaluation.isUsable) {
        println("  average held-out log-likelihood = ${"%.4f".format(evaluation.value / holdOut.size)}")
    } else {
        println("  average held-out log-likelihood = undefined")
        println()
        println("  It is undefined because at least one held-out value fell where the fitted")
        println("  mixture has no density at all. A mixture built from bounded pieces — uniform,")
        println("  triangular, beta — covers a union of intervals, not the whole line, and fresh")
        println("  data can land in a gap between them. The report treats how much data falls in")
        println("  such gaps as a result in its own right rather than as a nuisance.")
    }
    println()

    println("What to take from this")
    println("----------------------")
    println("A small Hellinger distance with the wrong families is the normal outcome, not a")
    println("surprise. The report measures it across the whole design: density recovery is good,")
    println("family recovery is under 50% even when the component count is right.")
    println()
    println("Example 6 shows how to run this on data of your own.")
}
