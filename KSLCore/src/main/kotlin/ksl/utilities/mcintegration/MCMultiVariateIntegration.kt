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

import ksl.utilities.random.mcmc.FunctionMVIfc
import ksl.utilities.random.rvariable.MVIndependentRV
import ksl.utilities.random.rvariable.MVRVariableIfc
import ksl.utilities.random.rvariable.UniformRV

/**
 * Provides for the integration of a multidimensional function via Monte-Carlo sampling.
 * The user is responsible for providing a function that when evaluated at the
 * sample from the provided sampler will evaluate to the desired integral over
 * the range of possible values of the sampler.
 *
 * The sampler must have the same range as the desired integral and the function's domain (inputs) must be consistent
 * with the range (output) of the sampler. There is no checking if the user does not supply appropriate functions or samplers.
 *
 * As an example, suppose we want the evaluation of the integral of g(x) over the range from a to b.
 * If the user selects the sampler as U(a,b) then the function to supply for the integration is NOT g(x).
 * The function should be h(x) = (b-a)*g(x).
 *
 * In general, if the sampler has pdf, w(x), over the range a to b. Then, the function to supply for integration
 * is h(x) = g(x)/w(x). Again, the user is responsible for providing a sampler that provides values over the interval
 * of integration.  And, the user is responsible for providing the appropriate function, h(x), that will result
 * in their desired integral.  This flexibility allows the user to specify h(x) in a factorization that supports an
 * importance sampling distribution as the sampler.
 *
 * See the detailed discussion for the class MCExperiment.
 * @see MCExperiment
 *
 * The evaluation will automatically utilize
 * antithetic sampling to reduce the variance of the estimates unless the user specifies not to do so. In the case of
 * using antithetic sampling, the sample size refers to the number of independent antithetic pairs observed. Thus, this
 * will require two function evaluations at each observation. The user can consider the implication of the cost of
 * function evaluation versus the variance reduction obtained.
 *
 * @param function the representation of h(x), must not be null
 * @param sampler  the sampler over the interval, must not be null
 * @param antitheticOptionOn  true represents use of antithetic sampling
 */
class MCMultiVariateIntegration(
    function: FunctionMVIfc,
    sampler: MVRVariableIfc,
    antitheticOptionOn: Boolean = true
) : MCExperiment() {
    init {
        require(function.dimension == sampler.dimension)
        { "The multi-variate function must have the same dimension as the multi-variate sampler" }
    }

    protected val myFunction: FunctionMVIfc = function
    protected val mySampler: MVRVariableIfc = sampler
    protected var myAntitheticSampler: MVRVariableIfc? = null

    init {
        if (antitheticOptionOn) {
            myAntitheticSampler = mySampler.antitheticInstance()
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

    override fun replication(j: Int): Double {
        return if (isAntitheticOptionOn) {
            val y1: Double = myFunction.f(mySampler.sample())
            val y2: Double = myFunction.f(myAntitheticSampler!!.sample())
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

fun main() {
    class TestFunc : FunctionMVIfc {
        override val dimension: Int = 2

        override fun f(x: DoubleArray): Double {
            require(x.size == dimension) { "The array size was not $dimension" }
            return 4.0 * x[0] * x[0] * x[1] + x[1] * x[1]
        }
    }

    val f = TestFunc()
    val sampler = MVIndependentRV(2, UniformRV(0.0, 1.0))
    val mc = MCMultiVariateIntegration(f, sampler)
    mc.confidenceLevel = 0.99
    mc.desiredHWErrorBound = 0.001

//        mc.runInitialSample();
//        System.out.println(mc);
    println()
    mc.runSimulation()
    println(mc)
}