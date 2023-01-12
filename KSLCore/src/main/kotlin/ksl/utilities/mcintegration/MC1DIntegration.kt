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

package ksl.utilities.mcintegration

import ksl.utilities.math.FunctionIfc
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.UniformRV


/**
 * Provides for the integration of a 1-D function via Monte-Carlo sampling.
 *
 * The evaluation will automatically utilize
 * antithetic sampling to reduce the variance of the estimates unless the user specifies not to do so. In the case of
 * using antithetic sampling, the micro replication sample size refers to the number of independent antithetic pairs observed. Thus, this
 * will require two function evaluations for each micro replication. The user can consider the implication of the cost of
 * function evaluation versus the variance reduction obtained.
 * The default confidence level has been set to 99 percent.
 *
 * Let f(x) be the probability distribution for the random variable supplied by the sampler.
 * Let g(x) be the function that needs to be integrated.
 * Let h(x) be a factorization of g(x) such that g(x) = h(x)*f(x), that is h(x) = g(x)/f(x)
 *
 * The interval of integration is defined based on the domain of f(x).
 *
 * @param function the representation of h(x), must not be null
 * @param sampler  the sampler over the interval, must not be null
 * @param antitheticOption  true represents use of antithetic sampling
 */
class MC1DIntegration (
    function: FunctionIfc,
    sampler: RVariableIfc,
    antitheticOption: Boolean = true
) : MCExperiment() {
    protected val myFunction: FunctionIfc
    protected val mySampler: RVariableIfc
    protected var myAntitheticSampler: RVariableIfc? = null

    init {
        myFunction = function
        mySampler = sampler
        if (antitheticOption){
            myAntitheticSampler = sampler.antitheticInstance()
        }
        confidenceLevel = 0.99
    }

    override fun runSimulation(): Double {
        if (resetStreamOption) {
            mySampler.resetStartStream()
            if (isAntitheticOptionOn) {
                myAntitheticSampler!!.resetStartStream()
            }
        }
        return super.runSimulation()
    }

    override fun replication(r: Int): Double {
        return if (isAntitheticOptionOn) {
            val y1 = myFunction.f(mySampler.sample())
            val y2 = myFunction.f(myAntitheticSampler!!.sample())
            (y1 + y2) / 2.0
        } else {
            myFunction.f(mySampler.sample())
        }
    }

    /**
     *
     * @return true if the antithetic option is on
     */
    val isAntitheticOptionOn: Boolean
        get() = myAntitheticSampler != null

}
