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

/** Where a criterion's preferred number of components sits in the range of counts fitted. */
enum class ComponentCountBoundary {

    /** The smallest count fitted. Usually a legitimate answer: no partition was preferred. */
    LOWER,

    /** Strictly inside the range: the criterion found a best rather than running out. */
    INTERIOR,

    /** The largest count fitted: the criterion ran out of candidates rather than finding a best. */
    UPPER,

    /** No count had a comparable value, so nothing was preferred. */
    NONE
}

/**
 *  What one criterion preferred, and whether the range of counts it was given decided that.
 *
 *  A preference at the smallest count fitted is usually a real answer, and a warning on it would be
 *  noise. The case worth reporting is narrower: the preference sits at the smallest count while the
 *  criterion is still improving at the largest. On a sample of 10,000 production lead times searched
 *  over one to six components, BIC preferred one component while still falling at six, and carried to
 *  twelve it crossed below the one-component value at ten. The range decided that answer, and
 *  [isUnfinished] says so.
 *
 *  @param criterionName the criterion's name
 *  @param numComponents the count it preferred, or null when no count had a comparable value
 *  @param boundary where that count sits in the range fitted
 *  @param stillImprovingAtTop whether the criterion improved from the second-largest to the largest
 *  count with a comparable value, in its own direction; false with fewer than two such counts
 */
data class CriterionChoice(
    val criterionName: String,
    val numComponents: Int?,
    val boundary: ComponentCountBoundary,
    val stillImprovingAtTop: Boolean
) {

    /**
     *  True when widening the range could change the preference: it sits at the largest count
     *  fitted, or at the smallest while the criterion was still improving at the largest.
     */
    val isUnfinished: Boolean
        get() = boundary == ComponentCountBoundary.UPPER ||
                (boundary == ComponentCountBoundary.LOWER && stillImprovingAtTop)

    companion object {

        /**
         *  Classifies a criterion's preference from its values.
         *
         *  @param criterionName the criterion's name
         *  @param comparableValues the criterion's value at each count where it was comparable
         *  @param countsFitted every count a candidate was fitted for, which sets the range
         *  @param smallerIsBetter the criterion's direction
         */
        fun classify(
            criterionName: String,
            comparableValues: Map<Int, Double>,
            countsFitted: Collection<Int>,
            smallerIsBetter: Boolean
        ): CriterionChoice {
            if (comparableValues.isEmpty() || countsFitted.isEmpty()) {
                return CriterionChoice(criterionName, null, ComponentCountBoundary.NONE, false)
            }
            val chosen = if (smallerIsBetter) {
                comparableValues.minBy { it.value }.key
            } else {
                comparableValues.maxBy { it.value }.key
            }
            val boundary = when (chosen) {
                countsFitted.max() -> ComponentCountBoundary.UPPER
                countsFitted.min() -> ComponentCountBoundary.LOWER
                else -> ComponentCountBoundary.INTERIOR
            }
            val ordered = comparableValues.keys.sorted()
            val improving = if (ordered.size < 2) {
                false
            } else {
                val top = comparableValues.getValue(ordered[ordered.size - 1])
                val before = comparableValues.getValue(ordered[ordered.size - 2])
                if (smallerIsBetter) top < before else top > before
            }
            return CriterionChoice(criterionName, chosen, boundary, improving)
        }
    }
}
