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
 *  A performance metric is figure of merit that characterizes the performance of
 *  a device, system, method, or entity, relative to its alternatives.
 *
 *  A metric has a required [name] to help identify it and an optional [description] to provide
 *  context for the metric.  The [domain] of the metric represents
 *  the natural set of possible (real) legal values for its values. The
 *  default interval for the domain is 0.0 to positive infinity. The [direction]
 *  provides the context for evaluation based on the metric value, with two cases:
 *  1) bigger values are considered better or 2) smaller values are considered
 *  better. The default direction is bigger is better. The metric can have
 *  a description of its units of measure [unitsOfMeasure].
 */
interface MetricIfc {
    enum class Direction {
        BiggerIsBetter, SmallerIsBetter
    }

    val name: String
    val domain: Interval
    val direction: Direction
    val unitsOfMeasure: String?
    val description: String?

    /**
     *  Indicates if the lower limit of the domain may be
     *  adjusted during scaling processes
     */
    val allowLowerLimitAdjustment: Boolean

    /**
     *  Indicates if the upper limit of the domain may be
     *  adjusted during scaling processes
     */
    val allowUpperLimitAdjustment: Boolean

    /**
     *  Returns a valid score that has the worst possible value
     *  according to the direction of the meaning of better.
     */
    fun badScore() : Score {
        return if (direction == Direction.BiggerIsBetter){
            Score(this, domain.lowerLimit, true)
        } else {
            Score(this, domain.upperLimit, true)
        }
    }

    fun newInstance() : MetricIfc

}

/**
 *  This class serves as a base class for classes that implement the
 *  MetricIfc interface.  A metric is figure of merit that characterizes the performance of
 *  a device, system, method, or entity, relative to its alternatives.
 *
 *  A metric has a required [name] to help identify it and an optional [description] to provide
 *  context for the metric.  The [domain] of the metric represents
 *  the natural set of possible (real) legal values for its values. The
 *  default interval for the domain is 0.0 to Double.MAX_VALUE. The [direction]
 *  provides the context for evaluation based on the metric value, with two cases:
 *  1) bigger values are considered better or 2) smaller values are considered
 *  better. The default direction is smaller is better. The metric can have
 *  a description of its units of measure [unitsOfMeasure].
 */
open class Metric(
    override val name: String,
    final override val domain: Interval = Interval(0.0, Double.MAX_VALUE),
    override val allowLowerLimitAdjustment: Boolean = true,
    override val allowUpperLimitAdjustment: Boolean = true
) : MetricIfc {
    init {
        require(domain.width > 0.0) { "The width of the domain must be > 0.0. It was $domain" }
        require(domain.width.isFinite()) { "The width of the domain must be finite. It was $domain" }
    }

    override var direction = MetricIfc.Direction.SmallerIsBetter

    override var unitsOfMeasure: String? = null

    override var description: String? = null

    override fun toString(): String {
        return "Metric(name='$name', domain=$domain, direction=$direction, unitsOfMeasure=$unitsOfMeasure, description=$description)"
    }

    override fun newInstance(): Metric {
        // causes default domain to be used and not one that might have been rescaled.
        val m = Metric(this.name,
            allowLowerLimitAdjustment = this.allowLowerLimitAdjustment,
            allowUpperLimitAdjustment = this.allowUpperLimitAdjustment)
        m.direction = this.direction
        m.unitsOfMeasure = this.unitsOfMeasure
        m.description = this.description
        return m
    }

}
