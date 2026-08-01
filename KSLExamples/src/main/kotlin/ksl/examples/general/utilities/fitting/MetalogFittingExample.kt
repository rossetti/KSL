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

import ksl.app.dist.catalog.FittingCatalog
import ksl.utilities.distributions.metalog.Metalog4P
import ksl.utilities.distributions.metalog.MetalogBoundedness
import ksl.utilities.distributions.metalog.MetalogDistribution
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.ScoringResult
import ksl.utilities.distributions.fitting.estimators.MetalogParameterEstimator
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.statistic.Statistic

/**
 *  Fitting metalog distributions with the standard KSL fitting pipeline.
 *
 *  The metalog family is unusual among the families the KSL fits. Its quantile function is a
 *  linear combination of basis functions, so more terms buy more shape without any change of
 *  family and without a nonlinear search. Two terms is the logistic distribution; each further
 *  term adds flexibility. Boundedness is chosen the same way: supplying a finite bound selects a
 *  semi-bounded or bounded member of the same family.
 *
 *  The one thing worth knowing before starting is that the family is **opt in**. Twenty metalog
 *  estimators cover five term counts by four kinds of boundedness, and adding them to the default
 *  set would change the recommended distribution for data that existing code already fits. So a
 *  plain call to estimateAndEvaluateScores will never fit a metalog. The family has to be asked
 *  for, which is what the first two functions below show.
 */
fun main() {
    val data = sampleData()

    fitTheWholeFamily(data)
    println()
    fitOneMemberByCatalogId(data)
    println()
    compareAgainstTheClassicalFamilies(data)
    println()
    sampleFromTheFittedDistribution(data)
    println()
    whatTheWinningArityDoesAndDoesNotTellYou()
}

/**
 *  Lognormal observations standing in for something like a service time: positive, right skewed,
 *  and not exactly any of the families that will be fitted to it.
 */
private fun sampleData(size: Int = 1_000): DoubleArray {
    return LognormalRV(mean = 10.0, variance = 25.0, streamNum = 1).sample(size)
}

/**
 *  The usual entry point: hand the opted-in set to the modeler and read the ranking back.
 */
private fun fitTheWholeFamily(data: DoubleArray) {
    println("Fitting all ${PDFModeler.metalogEstimators.size} metalog estimators")
    println("=".repeat(70))

    val modeler = PDFModeler(data)
    // Automatic shifting is turned off deliberately. It bootstraps a confidence interval for the
    // minimum of the data to decide whether the data must be shifted to the right of zero, which
    // is a real cost on a large sample. No metalog needs it: a metalog carries a lower bound
    // natively, so every metalog estimator reports checkRange = false and can never consume a
    // shift. Leave shifting on when the classical families are in the same run.
    val results = modeler.estimateAndEvaluateScores(
        PDFModeler.metalogEstimators,
        automaticShifting = false
    )

    println("Top five by score:")
    printRanking(results.resultsSortedByScoring.take(5))

    val best = results.topResultByScore
    val fitted = best.distribution as MetalogDistribution
    println()
    println("Recommended: ${best.name}")
    println("  terms           = ${fitted.numTerms}")
    println("  boundedness     = ${fitted.boundedness}")
    println("  coefficients    = ${fitted.coefficients().joinToString { "%.4f".format(it) }}")
    println("  support         = ${fitted.domain()}")
    // A metalog's bounds are profiled from the data when they are not supplied. They fit well but
    // carry no physical meaning, so a bound that matters should be supplied rather than profiled.
    println("  lower bound     = ${fitted.lowerBound}")
    println("  upper bound     = ${fitted.upperBound}")
    println("  estimator said  : ${best.estimationResult.message}")
}

/**
 *  The same fit reached through the catalog, which is how the command line, the MCP server and
 *  the REST service name a distribution. The IDs are stable strings, safe to put in a
 *  configuration file, of the form metalog{terms}p-{boundedness}.
 */
private fun fitOneMemberByCatalogId(data: DoubleArray) {
    println("Fitting a single member named by its catalog id")
    println("=".repeat(70))

    val ids = listOf("metalog3p-lower-bounded", "metalog5p-lower-bounded", "metalog5p-unbounded")
    for (id in ids) {
        val descriptor = FittingCatalog.estimator(id)
        val result = PDFModeler(data).estimateParameters(descriptor.factory(), automaticShifting = false)
        val summary = if (result.success) {
            result.parameters!!.asDoubleMap().entries.joinToString { "${it.key}=${"%.4f".format(it.value)}" }
        } else {
            "failed: ${result.message}"
        }
        println("${id.padEnd(24)} family=${descriptor.familyId.padEnd(10)} $summary")
    }
}

/**
 *  Metalog and the default families in one run, ranked together. This is the comparison that
 *  matters: the metalog is not automatically better, it simply competes.
 */
private fun compareAgainstTheClassicalFamilies(data: DoubleArray) {
    println("Metalog against the default families")
    println("=".repeat(70))

    // Shifting is left on here, because the classical estimators in this set do use it.
    val estimators = PDFModeler.allEstimators + PDFModeler.metalogEstimators
    val results = PDFModeler(data).estimateAndEvaluateScores(estimators)
    val ranked = results.resultsSortedByScoring

    println("Top ten of ${ranked.size} scored distributions:")
    printRanking(ranked.take(10))

    val bestMetalog = ranked.first { it.distribution is MetalogDistribution }
    val bestClassical = ranked.first { it.distribution !is MetalogDistribution }
    val lognormal = ranked.firstOrNull { it.rvType == RVType.Lognormal }
    println()
    println("Best metalog   : rank %2d  %s".format(ranked.indexOf(bestMetalog) + 1, bestMetalog.name))
    println("Best classical : rank %2d  %s".format(ranked.indexOf(bestClassical) + 1, bestClassical.name))
    if (lognormal != null) {
        println("Lognormal      : rank %2d  %s".format(ranked.indexOf(lognormal) + 1, lognormal.name))
    }
    println()
    println(
        """
        Read that ranking carefully. The data was generated from a lognormal, and the lognormal
        did not win. The metalogs took the leading places. That is not evidence that a metalog
        is the better model here. It is what happens when twenty flexible fits are scored against
        nine rigid ones on criteria that reward closeness to the sample: the family with more
        freedom tracks the sample more closely, including the parts of the sample that are noise.

        This is exactly why the metalog estimators are opt in and absent from the default set. Had
        they been added to it, every existing fitting run would have started returning a metalog.

        When a named family scores close to a metalog, prefer the named one. It carries meaning a
        modeller can defend, has fewer parameters, and extrapolates in a way someone can reason
        about. Reach for the metalog when no named family fits, when the shape is multi-modal or
        awkwardly bounded, or when the distribution comes from expert judgement rather than data.
        """.trimIndent()
    )
}

/**
 *  A fitted metalog is an ordinary KSL distribution: it samples, it inverts, and it reports
 *  moments. The moments are the one place to be careful, because a semi-bounded metalog need not
 *  possess them.
 */
private fun sampleFromTheFittedDistribution(data: DoubleArray) {
    println("Sampling from a fitted metalog")
    println("=".repeat(70))

    val estimator = MetalogParameterEstimator(numTerms = 5, boundedness = MetalogBoundedness.LowerBounded)
    val result = PDFModeler(data).estimateParameters(estimator, automaticShifting = false)
    val fitted = PDFModeler.createDistribution(result.parameters!!) as MetalogDistribution

    val rv = fitted.randomVariable(streamNumber = 9)
    val generated = Statistic(rv.sample(10_000))
    val observed = Statistic(data)

    println("                     observed      generated")
    println("  average       %14.4f %14.4f".format(observed.average, generated.average))
    println("  std deviation %14.4f %14.4f".format(observed.standardDeviation, generated.standardDeviation))
    println("  minimum       %14.4f %14.4f".format(observed.min, generated.min))
    println("  maximum       %14.4f %14.4f".format(observed.max, generated.max))

    println()
    println("Quantiles of the fitted distribution:")
    for (p in doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95, 0.99)) {
        println("  %5.2f -> %10.4f".format(p, fitted.invCDF(p)))
    }

    println()
    // A lower-bounded metalog exponentiates its quantile function, so a heavy enough tail leaves
    // the mean or the variance undefined. The distribution reports a non-finite value rather than
    // inventing one, and this predicate says in advance whether to trust them.
    println("Moments up to order 2 are reliable: ${fitted.momentsAreReliable(order = 2)}")
    println("  mean     = ${fitted.mean()}")
    println("  variance = ${fitted.variance()}")
}

/**
 *  What a metalog fit does and does not establish.
 *
 *  This one is worth running before trusting a metalog fitting report. Data is generated from a
 *  metalog whose parameters are known exactly, the whole family is fitted to it, and the result is
 *  compared against the truth in two different ways: how close the fitted *distribution* is, and
 *  whether the fitted *arity and boundedness* are the ones that generated the data.
 *
 *  The two answers are very different, and knowing which is which keeps a report from being
 *  over-read.
 */
private fun whatTheWinningArityDoesAndDoesNotTellYou() {
    println("Fitting data whose true distribution is known")
    println("=".repeat(70))

    // The generating distribution. Nothing about it is hidden from the fit; the point is to see
    // what the fit can and cannot tell us about it.
    val truth = Metalog4P(a1 = 10.0, a2 = 2.0, a3 = 0.6, a4 = 1.5)
    println("generating distribution: $truth")
    println()

    val provider = RNStreamProvider()
    println("       n   KS distance   terms  boundedness    fitted coefficients")
    for ((index, size) in listOf(200, 1_000, 5_000, 25_000).withIndex()) {
        val rv = truth.randomVariable(streamNumber = 11 + index, streamProvider = provider)
        val data = DoubleArray(size) { rv.value }
        val results = PDFModeler(data)
            .estimateAndEvaluateScores(PDFModeler.metalogEstimators, automaticShifting = false)
        val fitted = results.topResultByScore.distribution as MetalogDistribution
        val distance = kolmogorovDistance(fitted, truth)
        println("  %6d %10.4f %7d  %-13s  %s".format(
            size, distance, fitted.numTerms, fitted.boundedness,
            fitted.coefficients().joinToString { "%.3f".format(it) }))
    }

    println()
    println(
        """
        Two different things are happening in those columns.

        The Kolmogorov distance shrinks as the sample grows. That is the fit recovering the
        distribution, and it is the part to rely on. At a thousand observations a distance of
        roughly 0.02 is already inside the sampling noise of the data itself, meaning the fitted
        distribution is not distinguishable from the true one by the data that produced it.

        The last two columns are not converging on 4 and Unbounded, and they will not. The arities
        nest: a four-term metalog whose fourth coefficient is small is nearly a three-term metalog,
        and no amount of data separates them. An unbounded metalog and a lower-bounded one whose
        bound sits far from the data agree everywhere the data lives. So several candidates fit any
        given sample equally well, and which of them is ranked first is close to arbitrary.

        The coefficients make the same point from the other direction. One of the rows above
        happened to be won by an unbounded four-term fit, and its coefficients came back close to
        the generating ones. That is not a result to count on, and the rows around it show why: a
        larger sample produced a closer fit whose coefficients resemble the truth not at all.
        Coefficients are only comparable between fits of the same boundedness, because each member
        of the family works in its own fitting space. An unbounded metalog's coefficients are in
        the units of the data; a lower-bounded one's are in the units of the logarithm of the data
        above its bound. Comparing the two directly is meaningless.

        The practical rule: read the fitted distribution, not the label it arrives under. A report
        saying a six-term lower-bounded metalog won is not evidence that the data came from one. If
        the number of terms itself matters to you, fix it rather than letting the ranking choose,
        by fitting a single estimator of the arity you want.
        """.trimIndent()
    )
}

/** The largest gap in probability between two distributions, on a grid of the second one's quantiles. */
private fun kolmogorovDistance(fitted: MetalogDistribution, truth: MetalogDistribution): Double {
    var largest = 0.0
    var p = 0.005
    while (p < 1.0) {
        largest = maxOf(largest, kotlin.math.abs(fitted.cdf(truth.invCDF(p)) - p))
        p += 0.005
    }
    return largest
}

private fun printRanking(results: List<ScoringResult>) {
    // The name is the distribution's toString, which spells out every coefficient. The type and
    // the score are what a ranking is read for, so lead with those.
    for ((index, result) in results.withIndex()) {
        println("  %2d  %-16s %.4f   %s".format(
            index + 1, result.rvType.toString(), result.weightedValue, abbreviate(result.name)))
    }
}

/** The distribution's description with its coefficients rounded, so a ranking stays readable. */
private fun abbreviate(name: String): String {
    return Regex("""-?\d+\.\d+(E-?\d+)?""").replace(name) { match ->
        val value = match.value.toDouble()
        if (value.isFinite()) "%.3f".format(value) else match.value
    }
}
