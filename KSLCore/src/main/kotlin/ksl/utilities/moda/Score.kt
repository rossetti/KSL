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

/**
 *  A score represents an evaluation of an alternative, system, or entity based on some
 *  metric. Each score is related to a metric and has a [value]
 *  that represents the value of the metric. If there is an issue
 *  with computing the score, then the property [valid] indicates whether the
 *  score can be trusted (true) or not (false). The default value of the valid
 *  property is true. The supplied [value] must be within the specified
 *  domain of the supplied metric; otherwise, an illegal argument exception will
 *  occur.
 */
data class Score(
    val metric: MetricIfc,
    val value: Double,
    var valid: Boolean = true
) {
    init {
        require(metric.domain.contains(value)){"The supplied value = $value is not valid for the domain = ${metric.domain} of the metric."}
    }
}
