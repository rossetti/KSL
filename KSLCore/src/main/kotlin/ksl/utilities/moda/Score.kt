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

package ksl.utilities.moda

import ksl.utilities.Interval

/**
 *  A score represents an evaluation of an alternative or model based on some
 *  scoring context. Each score has a [name] to help identify it and a [value]
 *  that represents the value of the metric.  The [range] of the score represents
 *  the natural set of possible (real) legal values for the score's value. The
 *  default interval for the range is 0.0 to Double.MAX_VALUE. The [direction]
 *  provides the context for evaluation based on the score, with two cases:
 *  1) bigger values are considered better or 2) smaller values are considered
 *  better. The default direction is bigger is better. If there is an issue
 *  with computing the score, then the property [valid] indicates whether the
 *  score can be trusted (true) or not (false). The default value of the valid
 *  property is true.
 */
data class Score(
    val name: String,
    val value: Double,
    var range: Interval = Interval(0.0, Double.MAX_VALUE),
    var direction: Direction = Direction.BiggerIsBetter,
    var valid: Boolean = true
) {
    enum class Direction {
        BiggerIsBetter, SmallerIsBetter
    }
}
