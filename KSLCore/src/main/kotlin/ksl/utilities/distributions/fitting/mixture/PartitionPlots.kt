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

import ksl.utilities.io.plotting.BasePlot
import org.jetbrains.letsPlot.geom.geomDotplot
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomVLine
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.scale.scaleYContinuous

/**
 *  Figures about the sample and the partition, rather than about the fitted density.
 *
 *  These three live together because they share the dot plot and its bin width, and because they
 *  answer one question between them: **do the divisions this method is built from fall anywhere the
 *  data suggests?** The diagnostic panels a fit already carries — density, ECDF, QQ, PP — answer
 *  whether the fitted curve matches the sample, which is a different and easier question. A mixture
 *  can track a sample closely while its components are slices of a continuum, and the experiment
 *  says that is the common case.
 *
 *  They were written for the report's worked example and moved here so that any analyst can draw
 *  them. Three further figures stayed behind: they draw the truth beside the fit, which a tutorial
 *  has and an analyst does not.
 */

/**
 *  A sensible dot-plot bin width for a sample, when the caller has no reason to pick one.
 *
 *  A hundredth of the range. Dot plots are read for the shape of the stack rather than for exact
 *  heights, and a fixed count of bins keeps that shape comparable between samples on different
 *  scales. Any caller who cares should pass its own.
 */
internal fun defaultDotBinWidth(data: DoubleArray): Double {
    if (data.isEmpty()) return 1.0
    val range = (data.maxOrNull() ?: 0.0) - (data.minOrNull() ?: 0.0)
    return if (range <= 0.0) 1.0 else range / 100.0
}

/**
 *  Every observation, as a dot.
 *
 *  **The sample before any model touches it.** Worth drawing because the smoothed density that
 *  follows is persuasive in a way the raw sample is not: a reader shown three humps in a kernel
 *  estimate may take three components as evident, and the dots often show that they are not.
 *
 *  It carries no interpretation of its own, deliberately. What the dots mean is the analyst's
 *  reading, and a caption asserting how many clusters are visible would be putting a conclusion on
 *  a figure whose whole purpose is to let the reader form one.
 *
 *  @param data the sample
 *  @param binWidth the dot bin width, defaulting to a hundredth of the range
 *  @param variableName what the observations measure, for the axis label
 */
class SampleDotPlot(
    private val data: DoubleArray,
    private val binWidth: Double = defaultDotBinWidth(data),
    private val variableName: String = "observed value"
) : BasePlot() {

    init {
        require(binWidth > 0.0) { "The bin width must be > 0.0" }
        title = "The sample, one dot per observation"
        xLabel = variableName
        yLabel = ""
    }

    /**
     *  An optional caption drawn on the figure. Empty by default: the plot library does not wrap
     *  one, so a long caption runs off the canvas, and both destinations for this figure already
     *  carry a caption outside the image.
     */
    var caption: String = ""

    override fun buildPlot(): Plot {
        return letsPlot(mapOf("x" to data.toList())) +
                geomDotplot(binWidth = binWidth, color = "black", alpha = 0.5) { x = "x" } +
                scaleYContinuous(breaks = emptyList<Double>()) +
                (if (caption.isBlank()) labs(x = xLabel, y = yLabel, title = title)
                else labs(x = xLabel, y = yLabel, title = title, caption = caption)) +
                ggsize(width, height)
    }
}

/**
 *  The sample, with the cuts the method actually made on it.
 *
 *  **Where the partition meets the data.** Every component is estimated from its own group, so
 *  every reported component depends on two cut positions — and nothing else in a fit's output shows
 *  where they fell. This answers the question the density panels cannot: not whether the fitted
 *  curve is close, but whether the divisions it is assembled from land anywhere the data suggests.
 *
 *  Read it beside `PartitionStability`. That reports a three-valued verdict on whether the density
 *  follows the cuts; this shows the reader the arrangement the verdict is about. Cuts standing in
 *  visible gaps and cuts standing in the middle of a dense stack look nothing alike, and no summary
 *  statistic conveys the difference as directly.
 *
 *  Several counts may be drawn at once, which is how an analyst compares a count they proposed
 *  against the one that was recommended.
 *
 *  @param data the sample, which need not be sorted
 *  @param partitions the fitted partition at each component count of interest
 *  @param binWidth the dot bin width, defaulting to a hundredth of the range
 *  @param variableName what the observations measure, for the axis label
 */
class PartitionCutPlot(
    data: DoubleArray,
    private val partitions: Map<Int, DataPartition>,
    private val binWidth: Double = defaultDotBinWidth(data),
    private val variableName: String = "observed value"
) : BasePlot() {

    private val mySortedData: DoubleArray = data.sortedArray()

    init {
        require(binWidth > 0.0) { "The bin width must be > 0.0" }
        title = "Where the method cut, on the data it cut"
        xLabel = variableName
        yLabel = ""
    }

    /** An optional caption. Empty by default; see `AdmissibleCutPlot.caption` for why. */
    var caption: String = ""

    override fun buildPlot(): Plot {
        val x = mutableListOf<Double>()
        val kind = mutableListOf<String>()
        for (k in partitions.keys.sorted()) {
            val partition = partitions[k] ?: continue
            for (c in partition.cutPositions) {
                // A cut sits between two observations, so it is drawn between them rather than on
                // either one.
                if (c in 1 until mySortedData.size) {
                    x.add(0.5 * (mySortedData[c - 1] + mySortedData[c]))
                    kind.add("cuts at k = $k")
                }
            }
        }
        var p = letsPlot() +
                geomDotplot(
                    data = mapOf("x" to mySortedData.toList()),
                    binWidth = binWidth, color = "black", alpha = 0.30
                ) { this.x = "x" }
        if (x.isNotEmpty()) {
            p = p + geomVLine(
                data = mapOf("x" to x, "kind" to kind), size = 0.9
            ) { xintercept = "x"; color = "kind"; linetype = "kind" }
        }
        p = p + scaleYContinuous(breaks = emptyList<Double>())
        return p + (
                if (caption.isBlank())
                    labs(x = xLabel, y = yLabel, color = "", linetype = "", title = title)
                else labs(
                    x = xLabel, y = yLabel, color = "", linetype = "", title = title,
                    caption = caption
                )
                ) + ggsize(width, height)
    }
}

/**
 *  Which cut positions a run of equal values rules out.
 *
 *  **This is about ties, and only about ties.** A partition is defined by intervals of value, so
 *  observations that are equal must stay in the same group; a position inside a run of equal values
 *  is therefore not a position at all. That is the whole of what `AdmissibilityCertificate` decides
 *  per position — the minimum group size and the minimum distinct count are properties of a whole
 *  partition rather than of a single cut, and are not what this figure shows.
 *
 *  **On continuous data it has nothing to say**, because every observation is distinct and no
 *  position is ruled out. Check `hasRefusedPositions` before drawing it: a figure in which every
 *  mark is the same colour tells a reader nothing and invites them to think it is broken. It earns
 *  its place on data that has been rounded or recorded to a coarse unit, which is common enough in
 *  service-time data to be worth having.
 *
 *  @param data the sample, which need not be sorted
 *  @param certificate the admissibility certificate for that sample
 *  @param variableName what the observations measure, for the axis label
 */
class AdmissibleCutPlot(
    data: DoubleArray,
    private val certificate: AdmissibilityCertificate,
    private val variableName: String = "observed value"
) : BasePlot() {

    private val mySortedData: DoubleArray = data.sortedArray()

    init {
        // Neutral, because it is true whether or not anything is ruled out. A title naming
        // refusals reads as a broken figure on the common case where there are none.
        title = "Where a cut may fall"
        xLabel = variableName
        yLabel = ""
        // A strip rather than a panel. Every mark sits on one line, so the height a scatter plot
        // would want is empty space that makes the figure look like it failed to draw.
        height = 180
    }

    /**
     *  Whether any interior position is ruled out at all.
     *
     *  False for a sample with no ties, which is the common case for continuous measurements. A
     *  caller that draws the figure anyway gets one uniform colour and no information.
     */
    val hasRefusedPositions: Boolean
        get() {
            val admissible = certificate.admissibleCuts.toSet()
            return (1 until mySortedData.size).any { it !in admissible }
        }

    /** An optional caption. Left empty by default: the plot library does not wrap one, so a long
     *  caption runs off the canvas and is clipped. Explanation belongs in the surrounding text. */
    var caption: String = ""

    override fun buildPlot(): Plot {
        val admissible = certificate.admissibleCuts.toSet()
        val x = mutableListOf<Double>()
        val kind = mutableListOf<String>()
        for (i in 1 until mySortedData.size) {
            x.add(0.5 * (mySortedData[i - 1] + mySortedData[i]))
            kind.add(if (i in admissible) "a cut may fall here" else "inside a run of equal values")
        }
        val p = letsPlot(mapOf("x" to x, "kind" to kind)) +
                geomPoint(y = 0.0, size = 1.4, alpha = 0.55) { this.x = "x"; color = "kind" } +
                scaleYContinuous(breaks = emptyList<Double>())
        return p + (
                if (caption.isBlank()) labs(x = xLabel, y = yLabel, color = "", title = title)
                else labs(x = xLabel, y = yLabel, color = "", title = title, caption = caption)
                ) + ggsize(width, height)
    }
}

/**
 *  The real sample against a sample drawn from the fitted density, side by side.
 *
 *  **The check an analyst asks for first, and the one a test cannot replace.** A goodness-of-fit
 *  p-value says reject or do not reject; this shows *where* the fit departs from the data, which is
 *  what decides whether the departure matters. A fitted density that misses a small bump in the
 *  middle may be perfectly usable as a simulation input, while one that misses the upper tail is
 *  not, and no single number distinguishes those two cases.
 *
 *  **The picture is honest even where the formal test is not.** A two-sample test between these two
 *  samples would be invalid, because the drawn sample comes from a density fitted to the real one
 *  and so inherits its peculiarities — `FitAdequacy` records what that costs when measured. Looking
 *  at them together carries no such assumption: the eye is comparing two sets of numbers, not
 *  computing a null distribution.
 *
 *  Drawn at the same size as the real sample by default, so that the raggedness of the two is
 *  comparable. A larger drawn sample produces a smoother picture that flatters the fit, which is
 *  why it is not the default.
 *
 *  @param data the real observations
 *  @param fitted the fitted density
 *  @param numDrawn how many observations to draw from the fit; the real sample size by default
 *  @param streamNum the stream to draw with, so the figure is reproducible
 *  @param streamProvider the stream provider
 *  @param binWidth the dot bin width, defaulting to a hundredth of the range
 *  @param variableName what the observations measure, for the axis label
 */
class FittedAgainstDataPlot(
    data: DoubleArray,
    fitted: ksl.utilities.distributions.ContinuousDistributionIfc,
    numDrawn: Int = data.size,
    streamNum: Int = 1,
    streamProvider: ksl.utilities.random.rng.RNStreamProviderIfc =
        ksl.utilities.random.rvariable.KSLRandom.DefaultRNStreamProvider,
    private val binWidth: Double = defaultDotBinWidth(data),
    private val variableName: String = "observed value"
) : BasePlot() {

    private val myData: DoubleArray = data.sortedArray()
    private val myDrawn: DoubleArray =
        fitted.randomVariable(streamNum, streamProvider).sample(numDrawn).sortedArray()

    init {
        require(binWidth > 0.0) { "The bin width must be > 0.0" }
        require(numDrawn > 0) { "At least one observation must be drawn" }
        title = "The data, and a sample of the same size from the fit"
        xLabel = variableName
        yLabel = ""
    }

    /** An optional caption. Empty by default; see `AdmissibleCutPlot.caption` for why. */
    var caption: String = ""

    /** The drawn sample, so a caller can report on it rather than only look at it. */
    val drawnSample: DoubleArray
        get() = myDrawn.copyOf()

    override fun buildPlot(): Plot {
        val x = myData.toList() + myDrawn.toList()
        val which = List(myData.size) { "the data" } + List(myDrawn.size) { "drawn from the fit" }
        val p = letsPlot(mapOf("x" to x, "source" to which)) +
                geomDotplot(binWidth = binWidth, alpha = 0.45) { this.x = "x"; color = "source" } +
                scaleYContinuous(breaks = emptyList<Double>())
        return p + (
                if (caption.isBlank())
                    labs(x = xLabel, y = yLabel, color = "", title = title)
                else labs(x = xLabel, y = yLabel, color = "", title = title, caption = caption)
                ) + ggsize(width, height)
    }
}
