/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.utilities.distributions


/**
 * @author rossetti
 * @param theLossDistribution the distribution to shift
 * @param theShift the shift
 */
class ShiftedLossFunctionDistribution(theLossDistribution: LossFunctionDistributionIfc, theShift: Double) :
    ShiftedDistribution(theLossDistribution as DistributionIfc<*>, theShift, null), LossFunctionDistributionIfc {

    override fun firstOrderLossFunction(x: Double): Double {
        val cdf = distribution as LossFunctionDistributionIfc
        return cdf.firstOrderLossFunction(x - shift)
    }

    override fun secondOrderLossFunction(x: Double): Double {
        val cdf = distribution as LossFunctionDistributionIfc
        return cdf.secondOrderLossFunction(x - shift)
    }
}

fun main() {
    val SD = ShiftedLossFunctionDistribution(Poisson(1.0), 0.5)
    val p = Poisson(1.0)
    println("PMF_P(1) =" + p.pmf(1))
    println("CDF(1.5) =" + SD.cdf(1.5))
    println("CDF_P(1) =" + p.cdf(1))
    println("CCDF(1.5) =" + SD.complementaryCDF(1.5))
    println("FOLF(1.5) =" + SD.firstOrderLossFunction(1.5))
    println("SOLF(1.5) =" + SD.secondOrderLossFunction(1.5))
    println("FOLF_P(1) =" + p.firstOrderLossFunction(1.0))
    println("SOLF_P(1) =" + p.secondOrderLossFunction(1.0))
}