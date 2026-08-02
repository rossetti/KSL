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
 *  A metric that presents a different domain than the one it decorates, delegating everything
 *  else to the metric it was built from.
 *
 *  A MODA model may narrow a metric's domain to the range the alternatives actually realized, which
 *  makes the value function discriminate over the range that matters instead of over a nominal
 *  range that no alternative approaches. That adjustment used to be made by writing to the domain
 *  of the metric the caller supplied, which meant evaluating a model silently altered an object the
 *  caller still owned and might use elsewhere. Decorating instead of mutating keeps the adjustment
 *  inside the model.
 *
 *  The interval is copied rather than aliased, so later changes to the caller's interval cannot
 *  reach into the model, and changes here cannot reach back out.
 *
 *  This is deliberately internal: it is how rescaling is represented, not a concept callers need.
 *  Because it implements the interface rather than extending any particular class, it decorates any
 *  metric, including implementations outside this library, and asks nothing of them.
 */
internal class RescaledMetric(
    private val source: MetricIfc,
    rescaled: Interval
) : MetricIfc {

    override val domain: Interval = Interval(rescaled.lowerLimit, rescaled.upperLimit)

    override val name: String
        get() = source.name

    override val direction: MetricIfc.Direction
        get() = source.direction

    override val unitsOfMeasure: String?
        get() = source.unitsOfMeasure

    override val description: String?
        get() = source.description

    override val allowLowerLimitAdjustment: Boolean
        get() = source.allowLowerLimitAdjustment

    override val allowUpperLimitAdjustment: Boolean
        get() = source.allowUpperLimitAdjustment

    /**
     *  Returns a new instance of the decorated metric, not of the decoration. A new instance is
     *  wanted for its declared domain; carrying a rescaling into it would defeat the purpose.
     */
    override fun newInstance(): MetricIfc = source.newInstance()

    override fun toString(): String =
        "RescaledMetric(name='$name', domain=$domain, declared=${source.domain})"
}
