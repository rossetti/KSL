/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.distributions.fitting.estimators

/**
 *  Turns raw observations into the cumulative distribution function data that a metalog is
 *  parameterized by.
 *
 *  A metalog is fitted to points on a cumulative distribution function rather than to observations
 *  directly, so each sorted observation is paired with a plotting position. The convention used
 *  here places the i-th of m sorted values at a probability of one half less than i, divided by m,
 *  which keeps every probability strictly inside the unit interval, as the basis functions require.
 *
 *  For a large sample the data is instead resampled onto a fixed probability grid. That bounds the
 *  height of the design matrix without discarding the tails, since the grid is refined approaching
 *  each endpoint, which is where a metalog's shape flexibility is most needed and where evenly
 *  spaced probabilities carry the least information.
 */
object MetalogPlottingPositions {

    /**
     *  Above this many observations the sample is resampled onto a fixed probability grid rather
     *  than used point for point.
     */
    const val DEFAULT_RESAMPLE_THRESHOLD: Int = 100

    /**
     *  The spacing of the interior of the resampling grid.
     */
    const val DEFAULT_STEP: Double = 0.01

    /**
     *  The plotting positions for a sample of the given size.
     */
    fun positions(sampleSize: Int): DoubleArray {
        require(sampleSize >= 1) { "The sample size $sampleSize must be at least 1" }
        return DoubleArray(sampleSize) { (it + 0.5) / sampleSize }
    }

    /**
     *  Pairs the supplied observations with cumulative probabilities, returning the values first
     *  and the probabilities second. The values come back sorted, since a cumulative distribution
     *  function is being described.
     *
     *  @param data the observations, which are not modified
     *  @param resampleThreshold above this many observations, resample onto a fixed grid
     *  @param step the interior spacing of the resampling grid
     */
    fun cdfData(
        data: DoubleArray,
        resampleThreshold: Int = DEFAULT_RESAMPLE_THRESHOLD,
        step: Double = DEFAULT_STEP
    ): Pair<DoubleArray, DoubleArray> {
        require(data.size >= 2) { "There must be at least 2 observations, found ${data.size}" }
        require(step > 0.0) { "The step $step must be positive" }
        require(step < 0.5) { "The step $step must be less than 0.5" }
        require(data.all { it.isFinite() }) { "Every observation must be finite" }
        val sorted = data.sortedArray()
        if (sorted.size <= resampleThreshold) {
            return Pair(sorted, positions(sorted.size))
        }
        val probabilities = resamplingGrid(step)
        val values = DoubleArray(probabilities.size) { quantileOfSorted(sorted, probabilities[it]) }
        return Pair(values, probabilities)
    }

    /**
     *  The empirical quantile of an already-sorted sample, by linear interpolation between order
     *  statistics.
     *
     *  This is deliberately not the shared percentile helper, which sorts its argument on every
     *  call and does so in place. Resampling asks for a hundred or so quantiles from one sample, so
     *  routing each through a fresh sort would be wasteful and would mutate the caller's data.
     */
    private fun quantileOfSorted(sorted: DoubleArray, p: Double): Double {
        val n = sorted.size
        val position = p * (n + 1)
        if (position < 1.0) {
            return sorted[0]
        }
        if (position >= n) {
            return sorted[n - 1]
        }
        val lowerIndex = kotlin.math.floor(position).toInt() - 1
        val weight = position - kotlin.math.floor(position)
        return sorted[lowerIndex] + weight * (sorted[lowerIndex + 1] - sorted[lowerIndex])
    }

    /**
     *  The probability grid used when resampling: evenly spaced through the interior, and refined
     *  by a further order of magnitude approaching each endpoint.
     */
    fun resamplingGrid(step: Double = DEFAULT_STEP): DoubleArray {
        require(step > 0.0) { "The step $step must be positive" }
        require(step < 0.5) { "The step $step must be less than 0.5" }
        val points = sortedSetOf<Double>()
        var interior = step
        while (interior < 1.0) {
            points.add(interior)
            interior += step
        }
        val tailStep = step / 10.0
        var tail = tailStep
        while (tail < step) {
            points.add(tail)
            points.add(1.0 - tail)
            tail += tailStep
        }
        points.removeIf { (it <= 0.0) || (it >= 1.0) }
        return points.toDoubleArray()
    }
}
