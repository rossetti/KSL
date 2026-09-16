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
package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.distributions.fitting.mixture.refine.PartitionRefinerIfc
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureBICCriterion
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureCriterionIfc
import ksl.utilities.distributions.fitting.mixture.search.FamilySelectorIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.robj.DPopulation
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.Statistic

/**
 *  What repeated refitting on resampled data said about a recommendation.
 *
 *  @param componentCountFrequency how often each number of components was recommended
 *  @param familyByPosition for each component position, how often each family was chosen, counted
 *  only over resamples that recommended the modal number of components
 *  @param modalComponentCount the most frequently recommended number of components
 *  @param numSamples the resamples attempted
 *  @param numFitted the resamples that produced a recommendation
 *  @param distinctValues the number of distinct values in each resample, which matters here
 *  because cuts may not split tied observations
 *  @param isParametric whether resamples were drawn from the fitted mixture rather than from the
 *  data
 *  @param originalDistinctValues how many distinct values the original data holds, so that the
 *  resamples' reduction can be seen rather than inferred
 */
class MixtureBootstrapResults(
    val componentCountFrequency: IntegerFrequency,
    val familyByPosition: Map<Int, Map<String, Int>>,
    val modalComponentCount: Int?,
    val numSamples: Int,
    val numFitted: Int,
    val distinctValues: Statistic,
    val isParametric: Boolean,
    val originalDistinctValues: Int = 0
) {

    /**
     *  The share of successful resamples that recommended the modal number of components.
     *
     *  This is the headline. A value near one means the data determine the number of components;
     *  a value near a half means the recommendation is close to a coin toss and should not be
     *  reported without saying so.
     */
    val stabilityOfModalCount: Double
        get() {
            val modal = modalComponentCount ?: return 0.0
            return componentCountFrequency.proportion(modal)
        }

    override fun toString(): String = buildString {
        appendLine("Bootstrap of the recommendation")
        appendLine("-".repeat(60))
        appendLine("  resampling      : ${if (isParametric) "parametric (from the fitted mixture)" else "nonparametric (from the data)"}")
        appendLine("  resamples       : $numSamples, of which $numFitted produced a recommendation")
        if (!isParametric) {
            appendLine(
                "  distinct values : ${"%.0f".format(distinctValues.average)} per resample on " +
                        "average, against $originalDistinctValues in the data"
            )
        }
        appendLine()
        appendLine("  recommended number of components across resamples:")
        val values = componentCountFrequency.values
        val counts = componentCountFrequency.frequencies
        for (i in values.indices) {
            val share = componentCountFrequency.proportion(values[i])
            appendLine(
                "    k = %-3d %6d  %5.1f%%  %s".format(
                    values[i], counts[i], 100.0 * share, "#".repeat((share * 40).toInt())
                )
            )
        }
        appendLine()
        modalComponentCount?.let {
            appendLine("  most often recommended: k = $it, on ${"%.1f".format(100.0 * stabilityOfModalCount)}% of resamples")
        }
        if (familyByPosition.isNotEmpty()) {
            appendLine()
            appendLine("  families chosen, among resamples recommending k = $modalComponentCount:")
            for ((position, families) in familyByPosition.toSortedMap()) {
                val top = families.toList().sortedByDescending { it.second }.take(3)
                appendLine(
                    "    component ${position + 1}: " +
                            top.joinToString(", ") { "${it.first} ${it.second}" }
                )
            }
        }
    }
}

/**
 *  Assesses how much a mixture recommendation depends on the particular sample it was fitted to.
 *
 *  A single fit reports one number of components and one family per component. Nothing in that
 *  report says whether a slightly different sample would have produced the same answer, and for
 *  this method it very often would not: measured across a designed experiment, the criterion
 *  recovers the true number of components on well under half of attempts. Refitting on resampled
 *  data turns that general warning into a measurement on the data actually in hand.
 *
 *  This is deliberately not part of `fit`. It costs a full fit per resample and a user should
 *  choose to pay that.
 *
 *  **A caution particular to this method.** Resampling with replacement produces repeated values,
 *  and a resample of size *n* contains only about 63% as many distinct values as the original.
 *  That matters more here than it does when fitting a single distribution, because a cut may not
 *  split tied observations and each group must contain a minimum number of distinct values. The
 *  distinct-value count is therefore reported alongside the frequencies, and a parametric
 *  alternative is offered which produces no ties at all — at the cost of assuming the fitted
 *  mixture is correct, which is the very thing being questioned. Neither is right on its own;
 *  disagreement between them is itself informative.
 */
object MixtureBootstrap {

    /** The resamples drawn when no number is given. */
    var defaultNumBootstrapSamples: Int = 400
        set(value) {
            require(value >= 2) { "There must be at least two resamples" }
            field = value
        }

    /**
     *  Refits the mixture on resamples of the data and tabulates what was recommended.
     *
     *  @param data the observations
     *  @param numBootstrapSamples how many resamples to draw
     *  @param numComponentsRange the numbers of components each refit considers
     *  @param fitter a factory for the component fitter; a fresh one per resample, since the
     *  fitter is wrapped in a cache keyed by group range and a cache carried across resamples
     *  would answer with another sample's fits
     *  @param criterion the criterion each refit ranks by
     *  @param refiner a factory for the refiner, fresh per resample for the same reason
     *  @param selector a factory for the family selector
     *  @param streamNum the stream to resample with
     *  @param streamProvider the stream provider
     */
    fun componentCountFrequency(
        data: DoubleArray,
        numBootstrapSamples: Int = defaultNumBootstrapSamples,
        numComponentsRange: IntRange = MixtureModeler.defaultNumComponentsRange,
        fitter: () -> ComponentFitterIfc = { PDFComponentFitter() },
        criterion: MixtureCriterionIfc = MixtureBICCriterion(),
        refiner: () -> PartitionRefinerIfc = MixtureModeler.defaultRefiner,
        selector: () -> FamilySelectorIfc = MixtureModeler.defaultSelector,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
    ): MixtureBootstrapResults {
        require(data.size >= 2) { "There must be at least two observations" }
        val population = DPopulation(data, streamNum, streamProvider)
        return tabulate(
            numBootstrapSamples, numComponentsRange, fitter, criterion, refiner, selector,
            isParametric = false, originalDistinctValues = data.distinct().size
        ) { population.sample(data.size) }
    }

    /**
     *  Refits the mixture on samples drawn from a fitted mixture rather than from the data.
     *
     *  Produces no repeated values, so the tie interaction described above does not arise. In
     *  exchange it assumes the supplied mixture is the truth, so it measures how well the
     *  procedure recovers a known answer rather than how much the data constrain it. Read it as
     *  the optimistic bound.
     *
     *  @param fitted the mixture to draw from, usually a previous recommendation
     *  @param sampleSize the size of each drawn sample, usually the original sample size
     */
    fun parametricComponentCountFrequency(
        fitted: ksl.utilities.distributions.ContinuousDistributionIfc,
        sampleSize: Int,
        numBootstrapSamples: Int = defaultNumBootstrapSamples,
        numComponentsRange: IntRange = MixtureModeler.defaultNumComponentsRange,
        fitter: () -> ComponentFitterIfc = { PDFComponentFitter() },
        criterion: MixtureCriterionIfc = MixtureBICCriterion(),
        refiner: () -> PartitionRefinerIfc = MixtureModeler.defaultRefiner,
        selector: () -> FamilySelectorIfc = MixtureModeler.defaultSelector,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
    ): MixtureBootstrapResults {
        require(sampleSize >= 2) { "The sample size must be at least two" }
        val rv = fitted.randomVariable(streamNum, streamProvider)
        return tabulate(
            numBootstrapSamples, numComponentsRange, fitter, criterion, refiner, selector,
            isParametric = true
        ) { rv.sample(sampleSize) }
    }

    private fun tabulate(
        numBootstrapSamples: Int,
        numComponentsRange: IntRange,
        fitter: () -> ComponentFitterIfc,
        criterion: MixtureCriterionIfc,
        refiner: () -> PartitionRefinerIfc,
        selector: () -> FamilySelectorIfc,
        isParametric: Boolean,
        originalDistinctValues: Int = 0,
        draw: () -> DoubleArray
    ): MixtureBootstrapResults {
        require(numBootstrapSamples >= 2) { "There must be at least two resamples" }
        val counts = IntegerFrequency(name = "recommended number of components")
        val distinct = Statistic("distinct values per resample")
        // families are recorded per resample and summarised afterwards, because which resamples
        // count is decided by the modal component count and that is not known until the end
        val recommendations = mutableListOf<Pair<Int, List<String>>>()

        for (b in 1..numBootstrapSamples) {
            val sample = draw()
            distinct.collect(sample.distinct().size.toDouble())
            val results = try {
                MixtureModeler(sample, fitter(), criterion).fit(
                    numComponentsRange = numComponentsRange,
                    refiner = refiner(),
                    selector = selector()
                )
            } catch (e: Exception) {
                // A resample can be structurally unfittable — too few distinct values, for
                // instance. That is information about the data, not an error to propagate.
                //
                // Broad because this wraps a whole fit, not one call, so the exception set is not
                // enumerable from here. Unlike the group fitter above, this one IS silent: the
                // skipped replication leaves no trace, so a bootstrap in which most resamples
                // failed reads the same as one in which none did. Narrowing would not fix that;
                // recording the count would, and that is a change to what this class reports.
                continue
            }
            val best = results.best ?: continue
            val k = best.candidate.numComponents
            counts.collect(k)
            recommendations.add(k to best.candidate.components.map { it.rvType.toString() })
        }

        val modal = recommendations.groupingBy { it.first }.eachCount()
            .maxByOrNull { it.value }?.key
        val byPosition = mutableMapOf<Int, MutableMap<String, Int>>()
        if (modal != null) {
            for ((k, families) in recommendations) {
                if (k != modal) continue
                for ((position, family) in families.withIndex()) {
                    val row = byPosition.getOrPut(position) { mutableMapOf() }
                    row[family] = (row[family] ?: 0) + 1
                }
            }
        }
        return MixtureBootstrapResults(
            componentCountFrequency = counts,
            familyByPosition = byPosition,
            modalComponentCount = modal,
            numSamples = numBootstrapSamples,
            numFitted = recommendations.size,
            distinctValues = distinct,
            isParametric = isParametric,
            originalDistinctValues = originalDistinctValues
        )
    }
}
