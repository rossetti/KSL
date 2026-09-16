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

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

/**
 *  How binned counts are turned into a density on a grid.
 *
 *  Behind an interface so that the strategy can be replaced without touching the statistics above
 *  it. The present implementation truncates the kernel; a transform-based convolution would be
 *  roughly ten times faster and is deliberately not taken yet, because it would cost the published
 *  library a dependency on every downstream classpath for the sake of one diagnostic. Isolating it
 *  here makes that decision one build-script line to revisit rather than a rewrite.
 */
internal interface KernelConvolution {

    /**
     *  The density on the grid the counts were binned onto.
     *
     *  @param binCounts how many observations fell in each cell
     *  @param binWidth the width of a cell
     *  @param bandwidth the kernel bandwidth
     */
    fun evaluate(binCounts: DoubleArray, binWidth: Double, bandwidth: Double): DoubleArray
}

/**
 *  A Gaussian kernel evaluated out to a fixed number of bandwidths and taken as zero beyond.
 *
 *  **The Gaussian is a correctness requirement here, not a convenience.** Silverman (1981) proves
 *  that for the Gaussian kernel the number of modes of the estimate is non-increasing in the
 *  bandwidth. That monotonicity is what makes "the smallest bandwidth giving at most k modes"
 *  well defined and bisection on it correct. The theorem fails for other kernels, so substituting
 *  one would silently invalidate the critical bandwidth rather than merely change it.
 *
 *  **The truncation width and the mode tolerance have to be chosen together.** An earlier draft of
 *  this work truncated at four bandwidths and called the remainder negligible. It is not: the
 *  Gaussian is 3.4e-04 of its peak there, which swamps the 1e-08 relative tolerance the mode count
 *  uses to decide whether a bump is real, so truncation error alone could create a spurious mode
 *  or erase a genuine one. At six bandwidths the residual is 1.5e-08, still comparable. At eight it
 *  is 1.3e-14, safely below. Cost is linear in the width, so the correct choice costs twice the
 *  wrong one and nothing else.
 *
 *  @param widthInBandwidths how far the kernel is evaluated before being taken as zero
 */
internal class TruncatedGaussianConvolution(
    val widthInBandwidths: Double = 8.0
) : KernelConvolution {

    init {
        require(widthInBandwidths > 0.0) { "The truncation width must be > 0.0" }
    }

    override fun evaluate(
        binCounts: DoubleArray,
        binWidth: Double,
        bandwidth: Double
    ): DoubleArray {
        require(binWidth > 0.0) { "The bin width must be > 0.0" }
        require(bandwidth > 0.0) { "The bandwidth must be > 0.0" }
        val m = binCounts.size
        val total = binCounts.sum()
        val density = DoubleArray(m)
        if (total <= 0.0) return density
        // how many cells the kernel reaches, at least one so that a bandwidth below the bin width
        // still contributes to its own cell rather than vanishing
        val reach = maxOf(1, ceil(widthInBandwidths * bandwidth / binWidth).toInt())
        val scale = 1.0 / (total * bandwidth * sqrt(2.0 * PI))
        val kernel = DoubleArray(reach + 1)
        for (d in 0..reach) {
            val u = d * binWidth / bandwidth
            kernel[d] = exp(-0.5 * u * u)
        }
        for (i in 0 until m) {
            val c = binCounts[i]
            if (c == 0.0) continue
            val from = maxOf(0, i - reach)
            val to = minOf(m - 1, i + reach)
            for (j in from..to) {
                val d = if (j >= i) j - i else i - j
                density[j] += c * kernel[d]
            }
        }
        for (i in 0 until m) density[i] *= scale
        return density
    }
}
