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

package ksl.examples.general.utilities.fitting

import ksl.utilities.distributions.Metalog3P
import ksl.utilities.distributions.MetalogDistribution
import ksl.utilities.statistic.Statistic

/**
 *  Building a distribution from expert judgement, with no data at all.
 *
 *  A modeller often has no observations for an activity but can get three numbers out of the
 *  person who does the work: an optimistic time, a most likely time, and a pessimistic time.
 *  The triangular distribution is the usual answer, and it is a poor one — it puts a corner at
 *  the mode and hard walls at both ends, none of which the expert meant.
 *
 *  A symmetric percentile triplet is a better question to ask and a better distribution to get.
 *  The expert is asked for three **quantiles**: a value the activity beats one time in ten, the
 *  even-money value, and a value it exceeds one time in ten. Three metalog terms reproduce three
 *  quantiles exactly, in closed form, with no fitting step. The result is smooth, and its tails
 *  extend past the elicited range instead of stopping dead at it.
 *
 *  Reference: Keelin (2016), Propositions 1 through 4.
 */
fun main() {
    elicitFromThreeQuantiles()
    println()
    boundingTheDistribution()
    println()
    notEveryTripletIsADistribution()
    println()
    theEffectOfTheElicitedProbability()
}

/**
 *  The base case. An expert says a repair usually takes about 40 minutes, seldom less than 20,
 *  and seldom more than 90.
 */
private fun elicitFromThreeQuantiles() {
    println("A repair time elicited as a symmetric percentile triplet")
    println("=".repeat(70))

    val optimistic = 20.0   // beaten one time in ten
    val median = 40.0       // even money
    val pessimistic = 90.0  // exceeded one time in ten

    val repairTime = Metalog3P.fromSPT(optimistic, median, pessimistic, name = "RepairTime")

    println("elicited: 10th = $optimistic, 50th = $median, 90th = $pessimistic")
    println("coefficients: ${repairTime.coefficients().joinToString { "%.4f".format(it) }}")
    println()
    // The three elicited points are reproduced exactly, because three terms fit three points.
    println("Reproduced quantiles:")
    for (p in doubleArrayOf(0.1, 0.5, 0.9)) {
        println("  %4.2f -> %8.4f".format(p, repairTime.invCDF(p)))
    }
    println()
    println("Interpolated and extrapolated:")
    for (p in doubleArrayOf(0.01, 0.25, 0.75, 0.99)) {
        println("  %4.2f -> %8.4f".format(p, repairTime.invCDF(p)))
    }
    println()
    describe(repairTime)
}

/**
 *  The same three numbers with a floor imposed. Without a bound the unbounded member extends over
 *  the whole real line, so a long enough tail eventually produces a negative repair time. Naming a
 *  lower bound moves the distribution onto the correct support without changing the elicitation.
 */
private fun boundingTheDistribution() {
    println("The same elicitation with a floor of ten minutes")
    println("=".repeat(70))

    val unbounded = Metalog3P.fromSPT(20.0, 40.0, 90.0)
    val bounded = Metalog3P.fromSPT(20.0, 40.0, 90.0, lowerBound = 10.0)

    println("unbounded: support ${unbounded.domain()}")
    println("bounded  : support ${bounded.domain()}")
    println()
    println("           unbounded    bounded")
    for (p in doubleArrayOf(0.001, 0.01, 0.1, 0.5, 0.9, 0.99, 0.999)) {
        println("  %5.3f %11.4f %10.4f".format(p, unbounded.invCDF(p), bounded.invCDF(p)))
    }
    println()
    println(
        "Both reproduce the three elicited quantiles. They differ only where the expert was not " +
                "asked, which is exactly where a bound should be allowed to act."
    )
    println()
    // A bounded metalog is still a KSL distribution, so it samples like any other.
    val sample = Statistic(bounded.randomVariable(streamNumber = 3).sample(10_000))
    println("10,000 variates: average %.4f, minimum %.4f, maximum %.4f"
        .format(sample.average, sample.min, sample.max))
    println()
    // That maximum is not a defect. A lower-bounded metalog exponentiates its quantile function,
    // so imposing a floor buys a heavier ceiling. Whether the tail is heavy enough to cost the
    // distribution its moments is worth checking before using one for an activity time.
    println("bounded: moments reliable to order 2 = ${bounded.momentsAreReliable(order = 2)}, " +
            "mean = %.4f, variance = %.4f".format(bounded.mean(), bounded.variance()))
    println("Impose an upper bound as well when the elicitation implies a ceiling:")
    val twoSided = Metalog3P.fromSPT(20.0, 40.0, 90.0, lowerBound = 10.0, upperBound = 240.0)
    println("  support ${twoSided.domain()}, " +
            "0.999 quantile %.4f, mean %.4f".format(twoSided.invCDF(0.999), twoSided.mean()))
}

/**
 *  Three terms can only express so much skewness. When the median sits too close to one of the
 *  outer quantiles, no valid three-term metalog exists, and asking for one fails rather than
 *  returning something that is not a distribution.
 */
private fun notEveryTripletIsADistribution() {
    println("Triplets three terms cannot represent")
    println("=".repeat(70))

    val triplets = listOf(
        Triple(20.0, 40.0, 90.0),   // moderately skewed: fine
        Triple(20.0, 25.0, 90.0),   // median close to the lower quantile
        Triple(20.0, 85.0, 90.0),   // median close to the upper quantile
    )
    for ((low, mid, high) in triplets) {
        val feasible = Metalog3P.isFeasibleSPT(low, mid, high)
        print("  (%.0f, %.0f, %.0f) feasible = %-5s".format(low, mid, high, feasible))
        if (feasible) {
            println("  -> constructed")
        } else {
            // Test first with the predicate, or catch the failure. Either way an invalid triplet
            // never becomes an invalid distribution.
            val message = try {
                Metalog3P.fromSPT(low, mid, high)
                "unexpectedly succeeded"
            } catch (e: IllegalArgumentException) {
                // The message names the offending coefficients and where the quantile function
                // turned around, so keep the diagnosis and drop only the coefficient listing.
                e.message?.substringAfter("metalog: ") ?: "rejected"
            }
            println("  -> $message")
        }
    }
    println()
    println(
        "A triplet three terms cannot hold is not a dead end. A bound often rescues it, because " +
                "the closed form is applied to the transformed quantiles:"
    )
    for (lowerBound in doubleArrayOf(0.0, 15.0, 19.0)) {
        val feasible = Metalog3P.isFeasibleSPT(20.0, 25.0, 90.0, lowerBound = lowerBound)
        println("  (20, 25, 90) with a lower bound of $lowerBound: feasible = $feasible")
    }
    println("Otherwise, fit more terms to more elicited quantiles.")
}

/**
 *  The elicited probability is a parameter of the question, not of the distribution. An expert
 *  more comfortable with a one-in-four judgement than a one-in-ten judgement can be asked for the
 *  quartiles instead, and the same closed form applies.
 */
private fun theEffectOfTheElicitedProbability() {
    println("Eliciting at the quartiles rather than the deciles")
    println("=".repeat(70))

    val atDeciles = Metalog3P.fromSPT(20.0, 45.0, 90.0, alpha = 0.1)
    val atQuartiles = Metalog3P.fromSPT(20.0, 45.0, 90.0, alpha = 0.25)

    println("(20, 45, 90) read two ways:")
    println("           alpha=0.10   alpha=0.25")
    for (p in doubleArrayOf(0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95)) {
        println("  %4.2f %11.4f %12.4f".format(p, atDeciles.invCDF(p), atQuartiles.invCDF(p)))
    }
    println()
    println(
        "The same three numbers describe a much wider distribution when they are read as " +
                "quartiles, since the expert is claiming only half the mass lies between them " +
                "rather than four fifths. Record which question was asked."
    )
    println()
    // The elicited probability also decides whether a triplet is representable at all. The
    // earlier repair time is a perfectly good decile elicitation and not a valid quartile one:
    // reading the outer values as quartiles asks the same spread to be carried by half as much
    // probability, which demands more skewness than three terms have.
    println("The same triplet can be valid at one elicited probability and not at another:")
    for (alpha in doubleArrayOf(0.1, 0.25)) {
        println("  (20, 40, 90) at alpha = %4.2f: feasible = %s"
            .format(alpha, Metalog3P.isFeasibleSPT(20.0, 40.0, 90.0, alpha = alpha)))
    }
    println()
    // The feasible band narrows as the elicited probability approaches the median, because the
    // two outer quantiles are being asked to carry less and less of the distribution's shape.
    println("How much skewness three terms tolerate, by elicited probability:")
    for (alpha in doubleArrayOf(0.05, 0.1, 0.25, 0.4)) {
        val feasible = (1..99).filter { medianPercent ->
            val median = 20.0 + (90.0 - 20.0) * medianPercent / 100.0
            Metalog3P.isFeasibleSPT(20.0, median, 90.0, alpha = alpha)
        }
        val width = if (feasible.isEmpty()) 0.0 else (feasible.max() - feasible.min()) / 100.0
        println("  alpha = %4.2f: medians spanning %.2f of the elicited range are representable"
            .format(alpha, width))
    }
}

private fun describe(distribution: MetalogDistribution) {
    println("support  = ${distribution.domain()}")
    println("moments reliable to order 2 = ${distribution.momentsAreReliable(order = 2)}")
    println("mean     = %.4f".format(distribution.mean()))
    println("variance = %.4f".format(distribution.variance()))
}
