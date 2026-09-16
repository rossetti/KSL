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

import kotlin.math.abs
import kotlin.math.ceil

/**
 *  Whether the fitted density is consistent with the data it was fitted to.
 */
enum class FitVerdict {

    /** The data give no reason to reject the fitted density. */
    CONSISTENT,

    /** The data are further from the fitted density than resampling from it would explain. */
    CONTRADICTED,

    /** Too few replicates were usable to say. */
    INCONCLUSIVE
}

/**
 *  One summary of the data set beside the same summary of the fitted density.
 *
 *  **The part of this class an input modeller is most likely to act on.** A test says reject or do
 *  not reject; this says how far off the fit is on a quantity a simulation is sensitive to. A
 *  queueing model driven by these service times responds to the mean and the coefficient of
 *  variation far more than to the shape between them, so a fit that reproduces both to within a
 *  percent is adequate for that purpose whatever a goodness-of-fit test says about it.
 *
 *  The fitted value is computed from the distribution rather than from a sample of it, so it
 *  carries no simulation error of its own.
 *
 *  @param name what is being compared
 *  @param fromData the value computed from the observations
 *  @param fromFit the value computed from the fitted density
 */
data class FunctionalAgreement(
    val name: String,
    val fromData: Double,
    val fromFit: Double
) {

    /** How far the fit is from the data, in the quantity's own units. */
    val absoluteError: Double
        get() = fromFit - fromData

    /**
     *  The error as a fraction of the value the data gives, or null when that value is zero and a
     *  relative error would be undefined rather than large.
     */
    val relativeError: Double?
        get() = if (fromData == 0.0) null else (fromFit - fromData) / fromData
}

/**
 *  Whether a fitted mixture reproduces the sample it came from.
 *
 *  **A different question from `CountAdequacy`, and usually the more important one.** That class
 *  asks whether the sample can settle how many components there are. This asks whether the density
 *  is a fair description of the data — which is what an input model is for. A mixture can be
 *  thoroughly wrong about its components while reproducing the density well enough to drive a
 *  simulation, and the designed experiment says that is the common case.
 *
 *  Three readings are offered because they answer to different circumstances.
 *
 *  **The bootstrap-calibrated test uses all the data and is correctly calibrated.** A plain
 *  goodness-of-fit p-value against the fitted density is optimistic: the density was estimated from
 *  the very data being tested, so it sits closer to them than a distribution specified in advance
 *  would. The bootstrap removes that by *refitting on every replicate*, so the same over-fitting is
 *  present on both sides of the comparison and cancels.
 *
 *  **Comparisons against held-out data were tried and dropped.** They look like they should need no
 *  calibration, since a density fitted to one sample is independent of another, and that reasoning
 *  is incomplete. Holding data out removes the *over-fitting* — the density is not tuned to those
 *  particular observations — but not the *estimation error*: the fitted density is still not the
 *  truth, and the standard Kolmogorov-Smirnov null assumes a fully specified distribution rather
 *  than an estimated one. Measured where the null was true by construction, a one-sample test of
 *  held-out data against the fit rejected 13.5% of the time at a stated 5%, and a two-sample test
 *  against a draw from the fit rejected 37.5%. The bootstrap rejected 3.6%. Only the bootstrap
 *  carries the estimation error on both sides of the comparison, which is why only it is offered.
 *
 *  **A two-sample test between the fitting data and a draw from the fit fails in the other
 *  direction**, by the same reasoning rather than by measurement: the draw inherits whatever the fit
 *  took from those data, so the statistic comes out too small and the test under-rejects —
 *  conservative in the one direction an adequacy check cannot afford. That arrangement was never
 *  measured here, so treat the direction as argued and the size as unknown. Note that it is *not*
 *  the 37.5% reading above, which used held-out data and therefore over-rejected.
 *
 *  Overlaying those same two samples as a **picture** is a different matter and is worth doing:
 *  `FittedAgainstDataPlot` draws it. A figure claims no level, so the dependence that invalidates
 *  the test does not spoil the display.
 *
 *  @param numObservations the size of the fitting sample
 *  @param observedStatistic the Kolmogorov-Smirnov distance between the sample and its own fit
 *  @param nullStatistics the same distance on each usable replicate drawn from the fit and refitted
 *  @param numReplicates how many replicates were attempted
 *  @param functionals summaries of the data beside the same summaries of the fit
 *  @param level the level at which the verdict is read
 */
class FitAdequacy(
    val numObservations: Int,
    val observedStatistic: Double?,
    nullStatistics: DoubleArray,
    val numReplicates: Int,
    val functionals: List<FunctionalAgreement> = emptyList(),
    val level: Double = defaultLevel
) {

    init {
        require(numObservations >= 0) { "The number of observations cannot be negative" }
        require(numReplicates >= 0) { "The number of replicates cannot be negative" }
        require(level > 0.0 && level < 1.0) { "The level must be strictly between 0 and 1" }
    }

    private val mySortedNull: DoubleArray = nullStatistics.sortedArray()

    /** The replicate distances, ascending. A copy, so the quantiles cannot be reordered. */
    val nullStatistics: DoubleArray
        get() = mySortedNull.copyOf()

    /** How many replicates produced a usable distance. */
    val numUsable: Int
        get() = mySortedNull.size

    /** The share of attempted replicates that produced one. */
    val usableShare: Double
        get() = if (numReplicates == 0) 0.0 else numUsable.toDouble() / numReplicates

    /**
     *  The bootstrap p-value, in the form that cannot return zero from a finite number of
     *  replicates, or null when there is nothing to compare.
     */
    val pValue: Double?
        get() {
            val observed = observedStatistic ?: return null
            if (mySortedNull.isEmpty()) return null
            return (1.0 + mySortedNull.count { it >= observed }) / (1.0 + mySortedNull.size)
        }

    /** The smallest p-value this many replicates could produce, whatever the data say. */
    val smallestAttainablePValue: Double
        get() = if (mySortedNull.isEmpty()) 1.0 else 1.0 / (1.0 + mySortedNull.size)

    /** Whether the bootstrap has the resolution to reject at this level at all. */
    val canReject: Boolean
        get() = smallestAttainablePValue <= level

    /**
     *  The reading, at the stated level. `INCONCLUSIVE` when the bootstrap could not reach the
     *  level however the data fell, or when too many replicates failed for the survivors to stand
     *  for the null.
     */
    val verdict: FitVerdict
        get() {
            val p = pValue ?: return FitVerdict.INCONCLUSIVE
            if (!canReject) return FitVerdict.INCONCLUSIVE
            if (usableShare < minimumUsableShare) return FitVerdict.INCONCLUSIVE
            return if (p <= level) FitVerdict.CONTRADICTED else FitVerdict.CONSISTENT
        }

    /** The replicate distance at the given proportion, by nearest rank, or null when none. */
    fun nullQuantile(proportion: Double): Double? {
        require(proportion in 0.0..1.0) { "The proportion must be within 0 to 1" }
        if (mySortedNull.isEmpty()) return null
        val rank = ceil(proportion * mySortedNull.size).toInt().coerceIn(1, mySortedNull.size)
        return mySortedNull[rank - 1]
    }

    /**
     *  The largest relative error over the functionals that have one, or null when none does.
     *
     *  Offered because it is the number an input modeller can put next to a tolerance. A fit whose
     *  every summary is within a few percent of the data is usable whatever a test says.
     */
    val largestRelativeError: Double?
        get() = functionals.mapNotNull { it.relativeError }.maxByOrNull { abs(it) }

    /** What must accompany any display of this verdict. */
    fun caveats(): List<String> = listOf(
        "The density was fitted to the data it is being tested against. The bootstrap corrects " +
                "for that by refitting on every replicate; a plain goodness-of-fit p-value against " +
                "this density would be optimistic.",
        "Consistency is not correctness. A wrong model that is flexible enough will not be " +
                "contradicted by a sample this size, and the component structure can be wrong " +
                "while the density is not.",
        "The p-value cannot fall below ${"%.4f".format(smallestAttainablePValue)} with " +
                "$numUsable usable replicates, whatever the data say."
    ) + if (functionals.isNotEmpty()) listOf(
        "The summaries are the more useful reading for a simulation. What a model's output " +
                "responds to is usually a few functionals of its input rather than the whole " +
                "density, so an agreement of a few percent on those may settle the question a " +
                "test cannot."
    ) else emptyList()

    /** A sentence an analyst can read. */
    fun explain(): String = when (verdict) {
        FitVerdict.CONSISTENT ->
            "A sample of $numObservations gives no reason to reject the fitted density: it sits " +
                    "${"%.4f".format(observedStatistic ?: 0.0)} from the data, and resampling " +
                    "from the fit and refitting puts that distance at p = ${formatP()}."
        FitVerdict.CONTRADICTED ->
            "A sample of $numObservations contradicts the fitted density. It sits " +
                    "${"%.4f".format(observedStatistic ?: 0.0)} from the data, further than " +
                    "refitting on data drawn from the fit explains (p = ${formatP()}). The " +
                    "density is not a fair description of this sample."
        FitVerdict.INCONCLUSIVE ->
            "A sample of $numObservations cannot say whether the fitted density is adequate. " +
                    "$numUsable of $numReplicates replicates were usable, which is " +
                    (if (!canReject) "too few to reject at a level of $level however the data fall."
                    else "too small a share for the survivors to stand for the null.")
    }

    private fun formatP(): String = pValue?.let { "%.4f".format(it) } ?: "--"

    override fun toString(): String = buildString {
        appendLine("Does the fitted density reproduce the sample?")
        appendLine("-".repeat(72))
        appendLine("  observations              $numObservations")
        appendLine("  distance to the data      " +
                (observedStatistic?.let { "%.4f".format(it) } ?: "not measurable"))
        for (p in listOf(0.50, 0.90, 0.95, 0.99)) {
            val q = nullQuantile(p) ?: continue
            appendLine("  refitted null ${"%3.0f".format(p * 100)}%        ${"%.4f".format(q)}")
        }
        appendLine("  p-value                   ${formatP()}")
        appendLine("  usable replicates         $numUsable of $numReplicates")
        appendLine("  verdict                   $verdict")
        if (functionals.isNotEmpty()) {
            appendLine()
            appendLine("  %-14s %14s %14s %10s".format("summary", "data", "fit", "relative"))
            for (f in functionals) {
                appendLine("  %-14s %14.4f %14.4f %10s".format(
                    f.name, f.fromData, f.fromFit,
                    f.relativeError?.let { "%+.2f%%".format(100.0 * it) } ?: "--"))
            }
        }
        appendLine()
        appendLine(explain())
        appendLine()
        for (c in caveats()) appendLine("  - $c")
    }

    companion object {

        /** Replicates drawn when none is specified. */
        var defaultNumReplicates: Int = 199
            set(value) {
                require(value >= 1) { "There must be at least one replicate" }
                field = value
            }

        /** The level at which a verdict is read when none is specified. */
        var defaultLevel: Double = 0.05
            set(value) {
                require(value > 0.0 && value < 1.0) { "The level must be strictly between 0 and 1" }
                field = value
            }

        /**
         *  The share of attempted replicates that must be usable before a verdict other than
         *  `INCONCLUSIVE` is given. Replicates fail on samples this procedure finds hard, so the
         *  survivors of a heavy attrition sit too low to stand for the null.
         */
        var minimumUsableShare: Double = 0.5
            set(value) {
                require(value > 0.0 && value <= 1.0) { "The share must be within 0 to 1" }
                field = value
            }

    }
}
