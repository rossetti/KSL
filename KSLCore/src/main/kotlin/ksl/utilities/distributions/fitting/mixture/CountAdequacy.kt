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

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.random.rvariable.RVParametersTypeIfc

/**
 *  Whether a sample is large enough for the number of components to be answerable at all.
 *
 *  Three values, and none of them is a guarantee.
 */
enum class AdequacyVerdict {

    /** The sample is far too small for the components in this fit to be told apart. */
    INSUFFICIENT,

    /** Large enough to be worth asking, small enough that the answer is unreliable. */
    MARGINAL,

    /**
     *  The information is present. **Not a statement that the answer is right** — the procedure
     *  is bounded by the fitter's catalog rather than by the data alone.
     */
    AMPLE
}

/**
 *  How much data the fitted component count needs, and whether this sample has it.
 *
 *  **The question this answers is the one that comes before the count.** An analyst who supplies
 *  a component count is entitled to know whether their sample could settle it, and to be told so
 *  before being shown a number that looks as though it did.
 *
 *  The governing quantity is the separability of the fitted mixture — how far it sits from the
 *  nearest mixture with one component fewer, measured as a Hellinger distance. Across the designed
 *  experiment, recovery of the component count depends on the sample size and that separability
 *  only through their product `n * separability^2`, and does so strongly: over 11,520 attempts the
 *  count was recovered on 4.4% of attempts this rates `INSUFFICIENT`, 39.3% of those it rates
 *  `MARGINAL`, and 69.7% of those it rates `AMPLE`.
 *
 *  **Four things must be said wherever this is displayed**, and `caveats` says them:
 *
 *  1. The separability is measured on the *fitted* mixture, not on the truth, so a poor fit gives
 *     a poor estimate. It is an order of magnitude, not a measurement.
 *  2. The constant relating sample size to separability was measured on one designed experiment.
 *     It is a rule of thumb whose transportability is untested.
 *  3. `AMPLE` means the information is present, not that the answer is right, and whether more data
 *     would help depends on something the analyst cannot observe. Where the fitter's catalog holds
 *     the truth's families, recovery keeps climbing with `n * separability^2` and reaches about 98%
 *     at the highest ratios measured. Where it does not, recovery peaks near 55% and then *falls*
 *     as data accumulates, because more data is more evidence for extra components to patch a
 *     family that cannot fit. The pooled figure of 77% averages those two and describes neither.
 *  4. The separability is an infimum approximated from above, and the required sample size varies
 *     as one over its square, so every figure here is a floor rather than an estimate.
 *
 *  @param hypothesisedCount the number of components the fit used
 *  @param separability the Hellinger distance to the nearest mixture with one component fewer
 *  @param sampleSize the number of observations the fit was made from
 *  @param verdict the reading of `adequacyRatio` against the measured curve
 */
data class CountAdequacy(
    val hypothesisedCount: Int,
    val separability: Double,
    val sampleSize: Int,
    val verdict: AdequacyVerdict
) {

    /**
     *  The quantity recovery actually follows: the sample size times the squared separability.
     */
    val adequacyRatio: Double
        get() = sampleSize * separability * separability

    /**
     *  The sample size at which recovery of this count reaches even odds, of the order of the
     *  measured constant over the squared separability.
     *
     *  A floor rather than a target, for the reason in the fourth caveat.
     */
    val sizeForEvenOdds: Int
        get() = if (separability <= 0.0) Int.MAX_VALUE
        else Math.ceil(evenOddsConstant / (separability * separability)).toInt()

    /**
     *  The four things that must accompany any display of this verdict.
     *
     *  **These are shown to an analyst looking at their own sample, so they say what the verdict
     *  is and what it is not, and report no outcome of the experiment behind it.** The rates the
     *  third caveat used to quote — recovery reaching 98% where the catalog holds the truth's
     *  families and falling where it does not — are findings about the method over synthetic
     *  truths, not evidence about the reader's data, and they belong in the project report. What
     *  survives is the part an analyst can act on: that the direction depends on something they
     *  cannot observe.
     */
    fun caveats(): List<String> = listOf(
        "The separability is measured on the fitted mixture rather than on the truth, so a poor " +
                "fit gives a poor estimate. Read it as an order of magnitude.",
        "The constant relating sample size to separability was measured on one designed " +
                "experiment. It is a rule of thumb, not a theorem.",
        "AMPLE means the information is present, not that the count is right. Whether more data " +
                "would help depends on whether the catalog of families can represent the truth, " +
                "which cannot be read off the sample.",
        "The separability is approximated from above and the sample size varies as one over its " +
                "square, so this is a floor rather than an estimate."
    )

    /**
     *  A sentence an analyst can read, with the verdict's own limitation attached to it.
     */
    fun explain(): String = when (verdict) {
        AdequacyVerdict.INSUFFICIENT ->
            "A sample of $sampleSize is too small to settle whether there are $hypothesisedCount " +
                    "components. Telling them apart at this separability takes on the order of " +
                    "$sizeForEvenOdds observations for even odds, and this fit has far fewer. " +
                    "The fitted density may still be useful; the component count should not be " +
                    "relied on."
        AdequacyVerdict.MARGINAL ->
            "A sample of $sampleSize is on the edge of settling whether there are " +
                    "$hypothesisedCount components. Even odds would take on the order of " +
                    "$sizeForEvenOdds observations. Treat the count as a working hypothesis " +
                    "rather than a finding."
        AdequacyVerdict.AMPLE ->
            "A sample of $sampleSize carries enough information to distinguish " +
                    "$hypothesisedCount components from ${hypothesisedCount - 1} at this " +
                    "separability; even odds would take about $sizeForEvenOdds. That the " +
                    "information is present does not make the count right: the procedure still " +
                    "has to find it."
    }

    companion object {

        /**
         *  The value of `n * separability^2` at which recovery of the component count reaches even
         *  odds, measured across the 192-case designed experiment.
         *
         *  A rule of thumb from one design rather than a theorem, which is why the second caveat
         *  says so wherever the verdict is shown.
         */
        var evenOddsConstant: Double = 4.2
            set(value) {
                require(value > 0.0) { "The even-odds constant must be positive" }
                field = value
            }

        /**
         *  Below this value of `n * separability^2` the sample is rated `INSUFFICIENT`.
         *
         *  Taken from the measured curve rather than chosen: recovery is 11.9% at a ratio of one,
         *  which is barely above the rate of guessing among the counts on offer.
         */
        var insufficientBelow: Double = 1.0
            set(value) {
                require(value > 0.0) { "The insufficiency threshold must be positive" }
                field = value
            }

        /**
         *  At or above this value of `n * separability^2` the sample is rated `AMPLE`.
         *
         *  Also taken from the curve: recovery reaches about 72% by a ratio of ten and is
         *  essentially flat afterwards, so more data past this point buys almost nothing.
         */
        var ampleAtOrAbove: Double = 10.0
            set(value) {
                require(value > 0.0) { "The ample threshold must be positive" }
                field = value
            }

        /**
         *  Reads a ratio against the measured curve.
         *
         *  @param adequacyRatio the sample size times the squared separability
         */
        fun verdictFor(adequacyRatio: Double): AdequacyVerdict = when {
            adequacyRatio < insufficientBelow -> AdequacyVerdict.INSUFFICIENT
            adequacyRatio < ampleAtOrAbove -> AdequacyVerdict.MARGINAL
            else -> AdequacyVerdict.AMPLE
        }

        /**
         *  Assesses whether a sample can support the component count of a fitted mixture.
         *
         *  Returns null when the question does not arise — a single-component fit has no count to
         *  settle — or when the separability could not be computed, which is reported as absent
         *  rather than as an adequate or inadequate verdict.
         *
         *  @param weights the fitted mixing weights
         *  @param components the fitted components
         *  @param types the fitted component families
         *  @param sampleSize the number of observations fitted
         */
        fun of(
            weights: DoubleArray,
            components: List<ContinuousDistributionIfc>,
            types: List<RVParametersTypeIfc>,
            sampleSize: Int
        ): CountAdequacy? {
            require(sampleSize > 0) { "The sample size must be positive" }
            if (components.size < 2) return null
            val bound = SeparabilityBound.boundFor(
                "fitted mixture", weights, components, types
            ) ?: return null
            val ratio = sampleSize * bound.hellinger * bound.hellinger
            return CountAdequacy(
                components.size, bound.hellinger, sampleSize, verdictFor(ratio)
            )
        }
    }
}
