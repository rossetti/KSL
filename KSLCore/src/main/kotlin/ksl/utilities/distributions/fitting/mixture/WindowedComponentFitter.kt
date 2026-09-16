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

/**
 *  Estimates each component from a window that reaches past its group into the neighbours,
 *  while leaving the assignment alone.
 *
 *  The method uses one set of intervals for two jobs: deciding which component an observation
 *  is attributed to, and deciding which data informs that component's estimate. This separates
 *  them. Assignment is unchanged — the same generators and refiners produce it, every
 *  observation still belongs to exactly one group, and the mixing weights are still the group
 *  proportions. Only the data behind each estimate widens.
 *
 *  The motivation is the truncation term of Proposition 2. A component estimated from a
 *  truncated slice of itself is being asked to match the slice rather than the component, and a
 *  narrow slice of almost anything looks flat. Widening the window shows the estimator more of
 *  the component's actual shape, at the cost of admitting more of its neighbours.
 *
 *  **At a fraction of zero this is the wrapped fitter, exactly.** Not approximately: the window
 *  computation short-circuits, so no arithmetic can separate the window from the group, and
 *  `WindowEquivalenceTest` checks that a whole experiment run through a zero-fraction wrapper
 *  reproduces the unwrapped one value for value.
 *
 *  **Wrap this in the cache, not the other way round.** The window is a pure function of the
 *  group's boundaries, the sample size and the fraction, so a cache keyed on the assignment
 *  range stays exactly correct and keeps its hit rate:
 *
 *  <pre>
 *      ComponentFitCache(WindowedComponentFitter(PDFComponentFitter(estimators), delta))
 *  </pre>
 *
 *  Wrapping the other way keys the cache on window ranges, which move whenever either edge
 *  does, and the hit rate falls for no benefit.
 *
 *  @param fitter the fitter that does the estimating
 *  @param delta how far the window reaches into each neighbour, as a fraction of the group's
 *  own size. Zero reproduces the wrapped fitter.
 */
class WindowedComponentFitter(
    private val fitter: ComponentFitterIfc,
    val delta: Double = defaultDelta
) : ComponentFitterIfc {

    init {
        require(delta >= 0.0) { "The window fraction must be non-negative. It was $delta" }
        require(delta.isFinite()) { "The window fraction must be finite" }
    }

    /**
     *  Fits the component for a group, estimating from the group's window.
     *
     *  The returned result carries the **group's** range, not the window's. Nothing downstream
     *  reads those fields today, but the result is the fit *for that group*, and reporting the
     *  window there would misdescribe it for any code that later does read them.
     *
     *  @param sortedData the observations in non-decreasing order
     *  @param startIndex the group's first index, inclusive
     *  @param endIndex the group's last index, exclusive
     */
    override fun fitGroup(
        sortedData: DoubleArray,
        startIndex: Int,
        endIndex: Int
    ): GroupFitResult {
        val window = EstimationWindows.windowFor(startIndex, endIndex, sortedData.size, delta)
        if (window.startIndex == startIndex && window.endIndex == endIndex) {
            return fitter.fitGroup(sortedData, startIndex, endIndex)
        }
        val fit = fitter.fitGroup(sortedData, window.startIndex, window.endIndex)
        return GroupFitResult(startIndex, endIndex, fit.candidates, fit.rejections)
    }

    /**
     *  The window this fitter would use for a group, without fitting anything.
     *
     *  @param startIndex the group's first index, inclusive
     *  @param endIndex the group's last index, exclusive
     *  @param numObservations the size of the whole sample
     */
    fun windowFor(startIndex: Int, endIndex: Int, numObservations: Int): EstimationWindow =
        EstimationWindows.windowFor(startIndex, endIndex, numObservations, delta)

    override fun toString(): String = "WindowedComponentFitter(delta=$delta, fitter=$fitter)"

    companion object {

        /**
         *  The fraction used when none is given.
         *
         *  Zero, so that constructing this class without a deliberate choice cannot silently
         *  change what the method does.
         *
         *  Where a fraction is wanted, the pilot puts it near one fifth. That is a compromise
         *  rather than an optimum: one tenth minimises the distance to the truth at poor
         *  separation but is the *worst* fraction for recovering the number of components,
         *  and one fifth is the only value that is good on distance, coverage and count at
         *  once. Which of those matters is a question for the caller, not for a default.
         */
        var defaultDelta: Double = 0.0
            set(value) {
                require(value >= 0.0) { "The default window fraction must be non-negative" }
                require(value.isFinite()) { "The default window fraction must be finite" }
                field = value
            }
    }
}
