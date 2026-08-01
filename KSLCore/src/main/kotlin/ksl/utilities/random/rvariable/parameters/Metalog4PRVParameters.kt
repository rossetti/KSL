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
package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.metalog.Metalog4P
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.metalog.Metalog4PRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  Parameters for a four-term metalog random variable.
 *
 *  Every parameter is a scalar double, so all of them participate in reporting, in database
 *  persistence, and in the parameter setter used for scripted parameter changes and designed
 *  experiments. An array-valued parameter would be dropped or rejected by each of those.
 *
 *  The defaults describe a standard logistic distribution, which is a valid metalog, so creating a
 *  random variable or a distribution from untouched defaults always succeeds. A bound left at
 *  infinity is absent, which is what makes the unbounded member the default.
 */
class Metalog4PRVParameters : RVParameters(
    rvClassName = RVType.Metalog4P.parametrizedRVClass.simpleName!!,
    rvType = RVType.Metalog4P
), CreateDistributionIfc {

    override fun fillParameters() {
        addDoubleParameter("a1", 0.0)
        addDoubleParameter("a2", 1.0)
        addDoubleParameter("a3", 0.0)
        addDoubleParameter("a4", 0.0)
        addDoubleParameter("lowerBound", Double.NEGATIVE_INFINITY)
        addDoubleParameter("upperBound", Double.POSITIVE_INFINITY)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val a1 = doubleParameter("a1")
        val a2 = doubleParameter("a2")
        val a3 = doubleParameter("a3")
        val a4 = doubleParameter("a4")
        val lowerBound = doubleParameter("lowerBound")
        val upperBound = doubleParameter("upperBound")
        return Metalog4PRV(
            a1, a2, a3, a4, lowerBound, upperBound, streamNumber, streamProvider
        )
    }

    override fun createDistribution(): Metalog4P {
        val a1 = doubleParameter("a1")
        val a2 = doubleParameter("a2")
        val a3 = doubleParameter("a3")
        val a4 = doubleParameter("a4")
        val lowerBound = doubleParameter("lowerBound")
        val upperBound = doubleParameter("upperBound")
        return Metalog4P(a1, a2, a3, a4, lowerBound, upperBound)
    }
}
