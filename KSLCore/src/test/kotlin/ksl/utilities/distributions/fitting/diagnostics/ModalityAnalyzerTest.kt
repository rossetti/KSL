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

import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.NormalRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  The modality diagnostic, and the three independent checks that make it trustworthy.
 *
 *  A bootstrap-calibrated test produces plausible p-values whether or not it is correct, so
 *  "it ran and gave numbers" is worth nothing here. Three things are asserted instead.
 *
 *  **Monotonicity is a theorem, so a violation is a defect.** Silverman (1981) proves that for the
 *  Gaussian kernel the mode count is non-increasing in the bandwidth. That is what makes "the
 *  smallest bandwidth giving at most k modes" well defined and bisection on it correct, so it is
 *  checked as a property over random samples rather than on one example.
 *
 *  **The direction of the p-value is the easiest thing to invert unnoticed**, and inverting it
 *  would reverse every conclusion drawn from the gate. It is pinned against samples whose answers
 *  are known in advance.
 *
 *  **The truncation width and the mode tolerance must agree.** Halving the width must change no
 *  mode count; if it does, the tolerance is below the truncation error and modes are being created
 *  or erased by an approximation rather than by the data.
 */
class ModalityAnalyzerTest {

    private fun unimodal(n: Int, stream: Int): DoubleArray =
        NormalRV(0.0, 1.0, streamNum = stream).sample(n)

    private fun bimodal(n: Int, stream: Int, separation: Double = 6.0): DoubleArray {
        val a = NormalRV(0.0, 1.0, streamNum = stream).sample(n / 2)
        val b = NormalRV(separation, 1.0, streamNum = stream + 1).sample(n - n / 2)
        return a + b
    }

    @Test
    fun `the mode count is non-increasing in the bandwidth`() {
        // The strongest check available, and it is a property rather than an example: Silverman's
        // theorem says this must hold for a Gaussian kernel, so any violation is a defect here.
        for (stream in 1..6) {
            val data = if (stream % 2 == 0) unimodal(300, stream) else bimodal(300, stream)
            val analyzer = ModalityAnalyzer(data)
            val bandwidths = analyzer.defaultTraceBandwidths()
            var previous = Int.MAX_VALUE
            for (h in bandwidths) {
                val count = analyzer.modeCountAt(h)
                assertTrue(
                    count <= previous,
                    "mode count rose from $previous to $count at bandwidth $h on stream $stream"
                )
                previous = count
            }
        }
    }

    @Test
    fun `the chosen kernel truncation has converged and a narrower one has not`() {
        // Tested in the units the choice was actually argued in.
        //
        // Two earlier versions of this test were wrong. The first required eight bandwidths and
        // four to give the same mode count, which contradicts the analysis the width came from:
        // four is *known* to be inadequate. The second required them to give a different mode
        // count, which asserts that a defect must surface -- and it need not, since whether a
        // density error flips a mode depends on the data.
        //
        // The claim being defended is about the density residual: at four bandwidths the Gaussian
        // is 3.4e-04 of its peak, far above the 1e-08 relative tolerance the mode count uses, and
        // at eight it is 1.3e-14, far below. So the residual is what gets measured.
        val data = bimodal(400, 11)
        val analyzer = ModalityAnalyzer(data)
        val counts = binned(analyzer, data)
        val binWidth = analyzer.grid()[1] - analyzer.grid()[0]
        val chosen = TruncatedGaussianConvolution(8.0)
        val wider = TruncatedGaussianConvolution(16.0)
        val tooNarrow = TruncatedGaussianConvolution(4.0)
        val tolerance = ModalityAnalyzer.defaultModeTolerance
        var worstConverged = 0.0
        var worstNarrow = 0.0
        for (h in analyzer.defaultTraceBandwidths()) {
            val reference = chosen.evaluate(counts, binWidth, h)
            worstConverged = maxOf(
                worstConverged, relativeDeviation(reference, wider.evaluate(counts, binWidth, h))
            )
            worstNarrow = maxOf(
                worstNarrow, relativeDeviation(reference, tooNarrow.evaluate(counts, binWidth, h))
            )
        }
        assertTrue(
            worstConverged < tolerance,
            "widening the truncation still moved the density by $worstConverged, which is above " +
                    "the $tolerance the mode count resolves; eight bandwidths has not converged"
        )
        assertTrue(
            worstNarrow > tolerance,
            "four bandwidths moved the density by only $worstNarrow, at or below the $tolerance " +
                    "the mode count resolves; the analysis that rejected it predicts about 3.4e-04"
        )
    }

    /** The largest difference between two densities, relative to the peak of the reference. */
    private fun relativeDeviation(reference: DoubleArray, other: DoubleArray): Double {
        val peak = reference.max()
        if (peak <= 0.0) return 0.0
        var worst = 0.0
        for (i in reference.indices) {
            worst = maxOf(worst, kotlin.math.abs(reference[i] - other[i]))
        }
        return worst / peak
    }

    private fun binned(analyzer: ModalityAnalyzer, data: DoubleArray): DoubleArray {
        val grid = analyzer.grid()
        val binWidth = grid[1] - grid[0]
        val counts = DoubleArray(grid.size)
        for (x in data) {
            val i = ((x - (grid[0] - 0.5 * binWidth)) / binWidth).toInt().coerceIn(0, grid.size - 1)
            counts[i] += 1.0
        }
        return counts
    }

    private fun modes(values: DoubleArray): Int {
        val peak = values.max()
        if (peak <= 0.0) return 0
        val tolerance = ModalityAnalyzer.defaultModeTolerance * peak
        var count = 0
        for (i in 1 until values.size - 1) {
            if (values[i] - values[i - 1] > tolerance && values[i] - values[i + 1] > tolerance) {
                count++
            }
        }
        return count
    }

    @Test
    fun `the critical bandwidth is the smallest one satisfying the hypothesis`() {
        val analyzer = ModalityAnalyzer(bimodal(300, 21))
        val h = analyzer.criticalBandwidth(1)
        assertTrue(analyzer.modeCountAt(h) <= 1, "the critical bandwidth must satisfy the hypothesis")
        assertTrue(
            analyzer.modeCountAt(0.85 * h) > 1,
            "a clearly smaller bandwidth must violate it, or the search stopped too high"
        )
    }

    @Test
    fun `a clearly bimodal sample needs more smoothing than a normal one`() {
        // The critical bandwidth is the statistic the test is built on, so its direction is pinned
        // before the p-value's is.
        val bimodalWidth = ModalityAnalyzer(bimodal(400, 31)).criticalBandwidth(1)
        val unimodalWidth = ModalityAnalyzer(unimodal(400, 33)).criticalBandwidth(1)
        assertTrue(
            bimodalWidth > unimodalWidth,
            "separated groups must take more smoothing to merge: $bimodalWidth against $unimodalWidth"
        )
    }

    @Test
    fun `the test rejects a bimodal sample and does not reject a normal one`() {
        // This is what pins the direction of the p-value. Inverting it would reverse every
        // conclusion the entry gate draws, and nothing else here would notice.
        val bimodalResult = ModalityAnalyzer(bimodal(400, 41)).test(1, numBootstrapSamples = 60)
        val unimodalResult = ModalityAnalyzer(unimodal(400, 43)).test(1, numBootstrapSamples = 60)
        assertTrue(
            bimodalResult.rejects(),
            "a clearly bimodal sample must reject one mode, p = ${bimodalResult.pValue}"
        )
        assertTrue(
            !unimodalResult.rejects(),
            "a normal sample must not reject one mode, p = ${unimodalResult.pValue}"
        )
    }

    @Test
    fun `two analyzers with their own providers reproduce the p-value exactly`() {
        // A bootstrap that cannot be reproduced is not evidence. The data is chosen so the p-value
        // is strictly inside its range: this assertion is worthless against a p-value pinned at
        // zero or one, and the version of it that existed before the stream was moved onto the
        // constructor passed for exactly that reason while the underlying bootstrap was not
        // reproducible at all.
        val data = bimodal(200, 51, separation = 1.7)
        val a = ModalityAnalyzer(data, streamNum = 1, streamProvider = RNStreamProvider())
            .test(1, numBootstrapSamples = 60)
        val b = ModalityAnalyzer(data, streamNum = 1, streamProvider = RNStreamProvider())
            .test(1, numBootstrapSamples = 60)
        assertTrue(
            a.pValue > 0.0 && a.pValue < 1.0,
            "a saturated p-value would make this test pass without testing anything; p = ${a.pValue}"
        )
        assertEquals(a.pValue, b.pValue)
        assertEquals(a.criticalBandwidth, b.criticalBandwidth)
    }

    @Test
    fun `resetting the stream repeats the answer, and not resetting does not`() {
        // The library's convention: repeated draws advance, and repeatability is something the
        // caller asks for. Both halves are asserted, because a class that silently reset every
        // call would pass the first and quietly break common random numbers.
        val analyzer = ModalityAnalyzer(
            bimodal(200, 53, separation = 1.7), streamNum = 1, streamProvider = RNStreamProvider()
        )
        val first = analyzer.test(1, numBootstrapSamples = 60).pValue
        val second = analyzer.test(1, numBootstrapSamples = 60).pValue
        analyzer.resetStartStream()
        val third = analyzer.test(1, numBootstrapSamples = 60).pValue
        assertTrue(
            first > 0.0 && first < 1.0,
            "a saturated p-value cannot distinguish advancing from resetting; p = $first"
        )
        assertEquals(first, third, "resetting the stream must repeat the answer")
        assertTrue(second != first, "without a reset the streams advance, as everywhere else")
    }

    @Test
    fun `an analyzer that only counts modes takes no stream`() {
        // The bootstrap builds a throwaway analyzer per replicate to count that replicate's modes.
        // If construction acquired streams, a single test would take hundreds and an experiment
        // thousands, which is what the provider's own warning limit exists to catch.
        val provider = RNStreamProvider()
        val analyzer = ModalityAnalyzer(bimodal(200, 55), streamProvider = provider)
        analyzer.criticalBandwidth(1)
        analyzer.modeTrace()
        analyzer.binSensitivity()
        assertEquals(
            0, provider.lastRNStreamNumber(),
            "deterministic work must not consume the stream sequence"
        )
    }

    @Test
    fun `the two randomness sources sit on their own streams`() {
        // One stream per source, so that common random numbers synchronize per source rather than
        // interleaving the resample index and the smoothing noise on one stream.
        val provider = RNStreamProvider()
        val analyzer = ModalityAnalyzer(bimodal(200, 57), streamNum = 4, streamProvider = provider)
        analyzer.test(1, numBootstrapSamples = 10)
        assertEquals(4, analyzer.streamNumber)
        assertTrue(
            provider.lastRNStreamNumber() >= 5,
            "the noise draws from the stream after the resample's, but only ${provider.lastRNStreamNumber()} were provided"
        )
    }

    @Test
    fun `the smoothed bootstrap preserves the sample variance`() {
        // Silverman's rescaling. Without it the resamples carry the data's variance plus the
        // smoothing noise, their critical bandwidths run large, and the test quietly becomes
        // conservative. Checked here on the construction rather than on its consequence.
        val data = bimodal(500, 61)
        val analyzer = ModalityAnalyzer(data)
        val h = analyzer.criticalBandwidth(1)
        val statistic = ksl.utilities.statistic.Statistic(data)
        val stream = KSLRandom.DefaultRNStreamProvider.rnStream(3)
        val mean = statistic.average
        val variance = statistic.variance
        val rescale = 1.0 / kotlin.math.sqrt(1.0 + h * h / variance)
        val resample = DoubleArray(4000) {
            val x = data[stream.randInt(0, data.size - 1)]
            mean + rescale * (x + h * KSLRandom.rNormal(0.0, 1.0, stream) - mean)
        }
        val got = ksl.utilities.statistic.Statistic(resample).variance
        assertTrue(
            kotlin.math.abs(got - variance) / variance < 0.15,
            "the resample variance $got should be near the sample variance $variance"
        )
    }

    @Test
    fun `persistence finds the widest span a count holds over`() {
        val trace = listOf(
            ModeTraceEntry(0.1, 3, doubleArrayOf()),
            ModeTraceEntry(0.2, 2, doubleArrayOf()),
            ModeTraceEntry(0.3, 2, doubleArrayOf()),
            ModeTraceEntry(0.4, 2, doubleArrayOf()),
            ModeTraceEntry(0.5, 1, doubleArrayOf()),
            ModeTraceEntry(0.6, 2, doubleArrayOf()),
            ModeTraceEntry(0.9, 1, doubleArrayOf())
        )
        val two = trace.persistence(2)
        assertNotNull(two)
        assertEquals(0.2, two.start)
        assertEquals(0.4, two.endInclusive, "the wider of the two spans at this count must win")
        assertNull(trace.persistence(7), "a count that never occurs has no span")
    }

    @Test
    fun `the density can be evaluated as a function for plotting`() {
        val analyzer = ModalityAnalyzer(bimodal(300, 71))
        val f = analyzer.density(analyzer.criticalBandwidth(1))
        val grid = analyzer.grid()
        assertTrue(f(grid[grid.size / 2]) > 0.0, "the density must be positive where the data is")
        assertEquals(0.0, f(grid.first() - 1.0e6), "and zero far outside the grid")
        // it must integrate to about one, which is what makes it a density rather than a shape
        val step = grid[1] - grid[0]
        val mass = grid.sumOf { f(it) } * step
        assertTrue(
            kotlin.math.abs(mass - 1.0) < 0.05,
            "the estimate should integrate to about one, got $mass"
        )
    }

    @Test
    fun `bin sensitivity reports a count per bin count`() {
        val analyzer = ModalityAnalyzer(bimodal(300, 81))
        val sensitivity = analyzer.binSensitivity(listOf(32, 128, 512))
        assertEquals(setOf(32, 128, 512), sensitivity.keys)
        assertTrue(sensitivity.values.all { it >= 0 })
    }

    @Test
    fun `degenerate input is refused rather than answered`() {
        assertFailsWith<IllegalArgumentException> { ModalityAnalyzer(doubleArrayOf(1.0)) }
        assertFailsWith<IllegalArgumentException> {
            ModalityAnalyzer(doubleArrayOf(2.0, 2.0, 2.0, 2.0))
        }
        assertFailsWith<IllegalArgumentException> {
            ModalityAnalyzer(doubleArrayOf(1.0, Double.NaN, 3.0))
        }
    }

    @Test
    fun `the assessment warns only when neither warrant is present`() {
        val unimodalAssessment = ModalityAnalyzer(unimodal(300, 91)).assess(numBootstrapSamples = 40)
        assertNull(
            unimodalAssessment.warningOrNull(hasMechanism = true),
            "a claimed mechanism is a warrant, so there is nothing to warn about"
        )
        val warning = unimodalAssessment.warningOrNull(hasMechanism = false)
        assertNotNull(warning, "unimodal data with no mechanism is exactly the case to warn about")
        assertTrue(warning.contains("Neither warrant"))
        assertTrue(
            warning.contains("proceed"),
            "the gate warns and proceeds; it must not read as a refusal"
        )

        val bimodalAssessment = ModalityAnalyzer(bimodal(400, 93)).assess(numBootstrapSamples = 40)
        assertNull(
            bimodalAssessment.warningOrNull(hasMechanism = false),
            "visible multimodality is a warrant on its own"
        )
    }

    @Test
    fun `the assessment reports what an analyst counted off a plot`() {
        val assessment = ModalityAnalyzer(bimodal(400, 101)).assess(numBootstrapSamples = 40)
        assertTrue(assessment.apparentModes >= 1)
        assertTrue(assessment.modeTrace.isNotEmpty())
        assertEquals(1, assessment.unimodalityTest.maxModes)
        assertTrue(
            assessment.toString().contains("bound the number of components from below"),
            "the report must state that modes are a floor, not a count"
        )
    }
}
