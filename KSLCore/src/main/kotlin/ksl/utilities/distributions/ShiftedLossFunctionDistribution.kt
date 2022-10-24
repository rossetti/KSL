/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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