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
package ksl.utilities.distributions.fitting.diagnostics

import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.robj.DPopulation
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.Statistic
import kotlin.math.abs
import kotlin.math.sqrt

/**
 *  One row of the mode trace: how many modes the estimate has at a bandwidth, and where they are.
 *
 *  @param bandwidth the smoothing bandwidth
 *  @param numModes how many modes the estimate has there
 *  @param modeLocations where those modes sit
 */
class ModeTraceEntry(
    val bandwidth: Double,
    val numModes: Int,
    val modeLocations: DoubleArray
) {
    override fun toString(): String =
        "h=${"%.5f".format(bandwidth)} modes=$numModes at " +
                modeLocations.joinToString(", ", "[", "]") { "%.3f".format(it) }
}

/**
 *  The widest span of bandwidths over which the mode count holds at a value, or null when it never
 *  does.
 *
 *  **This is what makes the trace answer a question rather than merely display data.** A mode count
 *  that holds across a wide band of bandwidths is structure; one that appears only in a narrow
 *  band at small bandwidth is noise that a little more smoothing erases.
 *
 *  @param numModes the count whose persistence is wanted
 */
fun List<ModeTraceEntry>.persistence(numModes: Int): ClosedRange<Double>? {
    var best: ClosedRange<Double>? = null
    var start: Double? = null
    var previous: Double? = null
    for (e in this.sortedBy { it.bandwidth }) {
        if (e.numModes == numModes) {
            if (start == null) start = e.bandwidth
            previous = e.bandwidth
        } else if (start != null) {
            val span = start..previous!!
            if (best == null || (span.endInclusive - span.start) >
                (best.endInclusive - best.start)
            ) best = span
            start = null
        }
    }
    if (start != null && previous != null) {
        val span = start..previous
        if (best == null || (span.endInclusive - span.start) > (best.endInclusive - best.start)) {
            best = span
        }
    }
    return best
}

/**
 *  The outcome of Silverman's test of "at most this many modes".
 *
 *  @param maxModes the hypothesis tested
 *  @param criticalBandwidth the smallest bandwidth at which the estimate has at most that many
 *  modes
 *  @param pValue the share of smoothed resamples needing at least as much smoothing
 *  @param numBootstrapSamples how many resamples the share was taken over
 */
class ModalityTestResult(
    val maxModes: Int,
    val criticalBandwidth: Double,
    val pValue: Double,
    val numBootstrapSamples: Int
) {

    /**
     *  Whether the hypothesis is rejected at a level.
     *
     *  A small p-value means heavy smoothing was needed to reduce the estimate to this many modes,
     *  which is evidence that the data has more structure than the hypothesis allows.
     *
     *  @param level the significance level
     */
    fun rejects(level: Double = 0.05): Boolean = pValue < level

    override fun toString(): String =
        "at most $maxModes modes: critical bandwidth ${"%.5f".format(criticalBandwidth)}, " +
                "p = ${"%.4f".format(pValue)} over $numBootstrapSamples resamples"
}

/**
 *  How many modes the data appears to have, and whether that appearance survives smoothing.
 *
 *  **Why this is here at all.** An analyst reaching for a mixture has usually looked at a plot and
 *  counted humps. That count is the input the fitting workflow is built around, so it is worth
 *  knowing whether it is a property of the data or of the bin width that happened to be chosen.
 *
 *  **Modes bound the component count from below, and only from below.** A two-component mixture of
 *  equal-variance normals is unimodal whenever its means differ by less than twice the standard
 *  deviation, so seeing one hump does not mean one component. Seeing three humps does mean at
 *  least three components. The test here is therefore evidence about a floor rather than about the
 *  count itself.
 *
 *  A class rather than an object because the operations share expensive state: the binning, the
 *  mode trace, the bisection and every bootstrap replicate reuse the same sorted array and bin
 *  grid. This follows `PDFModeler`, which caches its histogram for the same reason and splits
 *  instance members from stateless functions over supplied data in the same way.
 *
 *  **The stream is a property of the analyzer, not an argument to its methods.** That is the
 *  library's convention — `Bootstrap`, `DPopulation` and `RVariable` all acquire their stream once
 *  at construction and expose `RNStreamControlIfc` so that repositioning is something the caller
 *  asks for explicitly. Taking a stream number per call, as this class first did, cannot be made
 *  reproducible: an explicit number hands every caller the same stream object at whatever position
 *  the last caller left it, and a stream number of zero means *the next stream*, so consecutive
 *  calls draw from different streams. Both were measured; five identical calls at an explicit
 *  number returned five different p-values.
 *
 *  @param data the observations, which need not be sorted
 *  @param numBins how many cells the data is binned into
 *  @param streamNum the random number stream number, defaults to 0, which means the next stream
 *  @param streamProvider the provider of random number streams, defaults to the library's shared
 *  provider. Supply a provider of its own to an analyzer that must be independent of everything
 *  else drawing from the shared one — which is what concurrent callers need, since a stream is not
 *  safe to share between threads.
 */
class ModalityAnalyzer(
    data: DoubleArray,
    val numBins: Int = defaultNumBins,
    streamNum: Int = 0,
    val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
) : RNStreamControlIfc {

    init {
        require(data.size >= 2) { "At least two observations are needed" }
        require(numBins > 2) { "There must be more than two bins" }
        require(data.all { it.isFinite() }) { "Every observation must be finite" }
    }

    /**
     *  The observations in non-decreasing order.
     */
    val sortedData: DoubleArray = data.sortedArray()

    private val myStatistic: Statistic = Statistic(sortedData)

    private val myLower: Double = sortedData.first()
    private val myUpper: Double = sortedData.last()
    private val myRange: Double = myUpper - myLower

    init {
        require(myRange > 0.0) { "Every observation is identical; modality is not defined" }
    }

    private val myConvolution: KernelConvolution = TruncatedGaussianConvolution()

    /**
     *  The two randomness sources of the smoothed bootstrap, each on its own stream.
     *
     *  One stream per source, following the library's convention: a resample index and the
     *  smoothing noise are separate sources, and giving them separate streams is what lets common
     *  random numbers synchronize per source instead of interleaving both on one.
     *
     *  **Acquired lazily, which is a deliberate departure from `Bootstrap`.** The bootstrap builds
     *  a throwaway analyzer per replicate purely to count that replicate's modes, and those
     *  analyzers never resample. Acquiring eagerly would take two streams for each of them —
     *  hundreds per test, thousands over an experiment — and trip the provider's own warning about
     *  a conceptual misunderstanding. It would be right about that. An analyzer that only counts
     *  modes takes no stream at all.
     */
    private val myPopulation: DPopulation by lazy {
        DPopulation(sortedData, myStreamNumber, streamProvider)
    }

    private val myNoise: NormalRV by lazy {
        NormalRV(0.0, 1.0, myStreamNumber + 1, streamProvider)
    }

    // Resolved once, so that the two sources sit on adjacent streams even when the requested number
    // is zero -- which means "the next stream" and would otherwise resolve twice to two unrelated
    // places in the sequence.
    private val myStreamNumber: Int = if (streamNum != 0) streamNum
    else streamProvider.lastRNStreamNumber() + 1

    /**
     *  The number of the stream the resampling draws from. The smoothing noise draws from the next
     *  one.
     */
    val streamNumber: Int
        get() = myStreamNumber

    override var advanceToNextSubStreamOption: Boolean
        get() = myPopulation.advanceToNextSubStreamOption
        set(value) {
            myPopulation.advanceToNextSubStreamOption = value
            myNoise.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = myPopulation.resetStartStreamOption
        set(value) {
            myPopulation.resetStartStreamOption = value
            myNoise.resetStartStreamOption = value
        }

    override fun resetStartStream() {
        myPopulation.resetStartStream()
        myNoise.resetStartStream()
    }

    override fun resetStartSubStream() {
        myPopulation.resetStartSubStream()
        myNoise.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        myPopulation.advanceToNextSubStream()
        myNoise.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = myPopulation.antithetic
        set(value) {
            myPopulation.antithetic = value
            myNoise.antithetic = value
        }

    /**
     *  The grid the density is evaluated on, padded beyond the data so that a mode near an end is
     *  not clipped by the edge of the grid.
     */
    private val myGridPadding: Double =
        gridPaddingFraction * myRange + gridPaddingDeviations * myStatistic.standardDeviation
    private val myGridLower: Double = myLower - myGridPadding
    private val myGridUpper: Double = myUpper + myGridPadding
    private val myBinWidth: Double = (myGridUpper - myGridLower) / numBins
    private val myGrid: DoubleArray = DoubleArray(numBins) { myGridLower + (it + 0.5) * myBinWidth }

    private val myBinCounts: DoubleArray = DoubleArray(numBins).also { counts ->
        for (x in sortedData) {
            val index = ((x - myGridLower) / myBinWidth).toInt().coerceIn(0, numBins - 1)
            counts[index] += 1.0
        }
    }

    /**
     *  A bandwidth wide enough that the estimate is certainly unimodal, found by doubling.
     *
     *  Doubling rather than a formula because the bisection needs a bracket that is *known* to
     *  satisfy the hypothesis, and a rule of thumb is not a guarantee.
     */
    private fun upperBracket(maxModes: Int): Double {
        var h = 0.5 * myStatistic.standardDeviation.coerceAtLeast(myBinWidth)
        var guard = 0
        while (modeCountAt(h) > maxModes) {
            h *= 2.0
            if (++guard > maxDoublings) break
        }
        return h
    }

    /**
     *  The kernel density estimate at a bandwidth, over the analyzer's grid.
     *
     *  @param bandwidth the smoothing bandwidth
     */
    fun densityOnGrid(bandwidth: Double): DoubleArray =
        myConvolution.evaluate(myBinCounts, myBinWidth, bandwidth)

    /**
     *  The grid the density is evaluated on.
     */
    fun grid(): DoubleArray = myGrid.copyOf()

    /**
     *  The kernel density estimate at a bandwidth, as a function, so that it can be plotted by the
     *  library's existing density plot rather than by a new one.
     *
     *  @param bandwidth the smoothing bandwidth
     */
    fun density(bandwidth: Double): (Double) -> Double {
        val values = densityOnGrid(bandwidth)
        return { x ->
            when {
                x <= myGridLower || x >= myGridUpper -> 0.0
                else -> {
                    val position = (x - myGridLower) / myBinWidth - 0.5
                    val i = position.toInt().coerceIn(0, numBins - 1)
                    val j = (i + 1).coerceAtMost(numBins - 1)
                    val t = (position - i).coerceIn(0.0, 1.0)
                    values[i] * (1.0 - t) + values[j] * t
                }
            }
        }
    }

    /**
     *  How many modes the estimate has at a bandwidth.
     *
     *  @param bandwidth the smoothing bandwidth
     */
    fun modeCountAt(bandwidth: Double): Int = modesAt(bandwidth).size

    /**
     *  Where the modes of the estimate sit at a bandwidth.
     *
     *  @param bandwidth the smoothing bandwidth
     */
    fun modesAt(bandwidth: Double): DoubleArray {
        val values = densityOnGrid(bandwidth)
        val indices = modeIndices(values)
        return DoubleArray(indices.size) { myGrid[indices[it]] }
    }

    /**
     *  The smallest bandwidth at which the estimate has at most this many modes.
     *
     *  Well defined only because the kernel is Gaussian: Silverman (1981) proves the mode count is
     *  then non-increasing in the bandwidth, so the set of bandwidths satisfying the hypothesis is
     *  an interval unbounded above and bisection finds its lower end. With any other kernel this
     *  function would return an arbitrary member of a disconnected set.
     *
     *  @param maxModes the hypothesis
     */
    fun criticalBandwidth(maxModes: Int): Double {
        require(maxModes >= 1) { "The mode count hypothesis must be at least one" }
        var high = upperBracket(maxModes)
        var low = 0.0
        repeat(numBisections) {
            val mid = 0.5 * (low + high)
            if (mid <= 0.0) return@repeat
            if (modeCountAt(mid) <= maxModes) high = mid else low = mid
        }
        return high
    }

    /**
     *  Modes against bandwidth over a sweep, which is the display an analyst reads to see whether
     *  an apparent structure survives smoothing.
     *
     *  @param bandwidths the bandwidths to evaluate, in any order
     */
    fun modeTrace(bandwidths: DoubleArray = defaultTraceBandwidths()): List<ModeTraceEntry> =
        bandwidths.sorted().map { h ->
            val locations = modesAt(h)
            ModeTraceEntry(h, locations.size, locations)
        }

    /**
     *  A geometric sweep of bandwidths spanning the range over which the mode count changes.
     */
    fun defaultTraceBandwidths(numPoints: Int = defaultNumTracePoints): DoubleArray {
        require(numPoints > 1) { "There must be more than one bandwidth" }
        val high = upperBracket(1)
        val low = (0.02 * high).coerceAtLeast(0.25 * myBinWidth)
        val ratio = high / low
        return DoubleArray(numPoints) { low * Math.pow(ratio, it.toDouble() / (numPoints - 1)) }
    }

    /**
     *  Silverman's test of "at most this many modes", by the smoothed bootstrap.
     *
     *  Resamples are drawn from the estimate at the critical bandwidth itself and **rescaled to
     *  preserve the sample variance**. That rescaling is not optional: without it the resamples
     *  carry the variance of the data plus the variance of the smoothing noise, their own critical
     *  bandwidths run large, and the test becomes conservative in a way nothing would flag.
     *
     *  Repeated calls advance the streams, as everything else in the library does. For a repeatable
     *  answer call `resetStartStream` first, or give the analyzer a provider of its own.
     *
     *  @param maxModes the hypothesis
     *  @param numBootstrapSamples how many resamples to draw
     */
    fun test(
        maxModes: Int,
        numBootstrapSamples: Int = defaultNumBootstrapSamples
    ): ModalityTestResult {
        require(maxModes >= 1) { "The mode count hypothesis must be at least one" }
        require(numBootstrapSamples > 0) { "There must be at least one resample" }
        val observed = criticalBandwidth(maxModes)
        val n = sortedData.size
        val mean = myStatistic.average
        val variance = myStatistic.variance
        val rescale = 1.0 / sqrt(1.0 + observed * observed / variance)
        var atLeastAsLarge = 0
        repeat(numBootstrapSamples) {
            val drawn = myPopulation.sample(n)
            val resample = DoubleArray(n) { i ->
                mean + rescale * (drawn[i] + observed * myNoise.value - mean)
            }
            // A resample can be degenerate only in pathological cases; where it is, it carries no
            // information about the hypothesis and is skipped rather than counted either way.
            val analyzer = try {
                ModalityAnalyzer(resample, numBins)
            } catch (e: IllegalArgumentException) {
                null
            }
            if (analyzer != null && analyzer.criticalBandwidth(maxModes) >= observed) {
                atLeastAsLarge++
            }
        }
        return ModalityTestResult(
            maxModes, observed, atLeastAsLarge.toDouble() / numBootstrapSamples, numBootstrapSamples
        )
    }

    /**
     *  How the apparent mode count varies with the number of bins.
     *
     *  The persuasive half of the diagnostic: it shows that "I can see two humps" is partly a
     *  statement about a default bin width rather than only about the data.
     *
     *  @param binCounts the bin counts to try
     */
    fun binSensitivity(binCounts: List<Int> = defaultBinSensitivityCounts): Map<Int, Int> {
        val reference = criticalBandwidth(1)
        return binCounts.filter { it > 2 }.associateWith { bins ->
            ModalityAnalyzer(sortedData, bins).modeCountAt(reference * binSensitivityFraction)
        }
    }

    /**
     *  Everything the pre-fit description needs about modality, in one object.
     *
     *  @param numBootstrapSamples how many resamples the test draws
     */
    fun assess(
        numBootstrapSamples: Int = defaultNumBootstrapSamples
    ): ModalityAssessment {
        val trace = modeTrace()
        val counts = trace.map { it.numModes }.distinct()
        // the most persistent count, which is the honest reading of "how many humps are there"
        val apparent = counts.maxByOrNull { c ->
            val span = trace.persistence(c)
            if (span == null) 0.0 else span.endInclusive - span.start
        } ?: 1
        return ModalityAssessment(
            apparentModes = apparent,
            unimodalityTest = test(1, numBootstrapSamples),
            modePersistence = trace.persistence(apparent),
            binSensitivity = binSensitivity(),
            modeTrace = trace
        )
    }

    /**
     *  The indices of the grid cells that are modes.
     *
     *  A mode is a cell higher than both neighbours by more than a relative tolerance, so that
     *  numerical ripple across a flat region is not counted as structure. The tolerance is
     *  relative to the peak density, which is what makes it scale-free.
     */
    private fun modeIndices(values: DoubleArray): IntArray {
        val peak = values.max()
        if (peak <= 0.0) return IntArray(0)
        val tolerance = defaultModeTolerance * peak
        val found = mutableListOf<Int>()
        var i = 1
        while (i < values.size - 1) {
            val here = values[i]
            if (here - values[i - 1] > tolerance) {
                // walk across any plateau at this height, then require a fall on the far side
                var j = i
                while (j < values.size - 1 && abs(values[j + 1] - here) <= tolerance) j++
                if (j < values.size - 1 && here - values[j + 1] > tolerance) {
                    found.add((i + j) / 2)
                }
                i = j + 1
            } else {
                i++
            }
        }
        return found.toIntArray()
    }

    companion object {

        /**
         *  The number of cells the data is binned into when none is given.
         */
        var defaultNumBins: Int = 512
            set(value) {
                require(value > 2) { "There must be more than two bins" }
                field = value
            }

        /**
         *  The number of resamples the smoothed bootstrap draws when none is given.
         */
        var defaultNumBootstrapSamples: Int = 200
            set(value) {
                require(value > 0) { "There must be at least one resample" }
                field = value
            }

        /**
         *  How much a cell must exceed its neighbours, relative to the peak density, to count as a
         *  mode.
         *
         *  Chosen together with the kernel truncation width: at eight bandwidths the truncated
         *  Gaussian's residual is 1.3e-14, comfortably below this, so a mode cannot be created or
         *  erased by truncation alone.
         */
        var defaultModeTolerance: Double = 1.0e-8
            set(value) {
                require(value > 0.0) { "The mode tolerance must be > 0.0" }
                field = value
            }

        /**
         *  How many bandwidths the default mode trace sweeps.
         */
        var defaultNumTracePoints: Int = 60
            set(value) {
                require(value > 1) { "There must be more than one bandwidth" }
                field = value
            }

        /**
         *  The bin counts the sensitivity check tries.
         */
        var defaultBinSensitivityCounts: List<Int> = listOf(16, 32, 64, 128, 256, 512)

        /**
         *  The fraction of the unimodal critical bandwidth at which bin sensitivity is measured.
         *
         *  Below the critical bandwidth, so that there is structure to be sensitive about: at or
         *  above it every bin count would report one mode and the check would say nothing.
         */
        var binSensitivityFraction: Double = 0.5
            set(value) {
                require(value > 0.0 && value < 1.0) { "The fraction must be within (0, 1)" }
                field = value
            }

        /**
         *  How far the evaluation grid extends beyond the data, as a fraction of its range.
         */
        var gridPaddingFraction: Double = 0.15
            set(value) {
                require(value > 0.0) { "The padding fraction must be > 0.0" }
                field = value
            }

        /**
         *  Further padding of the evaluation grid, in sample standard deviations.
         *
         *  The range alone is not enough. A kernel of bandwidth `h` spreads mass roughly `3h`
         *  beyond the outermost observation, and the bandwidths of interest here run up to the one
         *  that merges the data into a single mode — which is of the order of the spread of the
         *  data, not of a small fraction of its range. Without this term the estimate loses
         *  visible mass off the ends of the grid at exactly the bandwidths the diagnostic cares
         *  about.
         *
         *  Mode counting is unaffected either way, since modes are interior. What this protects is
         *  `density`, which is offered for plotting and ought to integrate to one.
         */
        var gridPaddingDeviations: Double = 4.0
            set(value) {
                require(value >= 0.0) { "The padding must be >= 0.0" }
                field = value
            }

        /**
         *  How many bisection steps the critical bandwidth search takes.
         */
        var numBisections: Int = 40
            set(value) {
                require(value > 0) { "There must be at least one bisection step" }
                field = value
            }

        /**
         *  How many doublings the upper bracket search will attempt before giving up.
         */
        var maxDoublings: Int = 60
            set(value) {
                require(value > 0) { "There must be at least one doubling" }
                field = value
            }

        /**
         *  The mode count of a Gaussian kernel estimate of the supplied data at a bandwidth.
         *
         *  Stateless, over data supplied by the caller, following `PDFModeler`'s companion. Builds
         *  an analyzer internally, so a caller asking repeatedly should hold one instead.
         *
         *  @param data the observations
         *  @param bandwidth the smoothing bandwidth
         *  @param numBins how many cells to bin into
         */
        fun modeCount(
            data: DoubleArray,
            bandwidth: Double,
            numBins: Int = defaultNumBins
        ): Int = ModalityAnalyzer(data, numBins).modeCountAt(bandwidth)
    }
}
