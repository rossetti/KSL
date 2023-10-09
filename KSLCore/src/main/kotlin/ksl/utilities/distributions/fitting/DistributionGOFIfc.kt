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

package ksl.utilities.distributions.fitting

import ksl.utilities.statistic.HistogramIfc

interface DistributionGOFIfc {
    val numEstimatedParameters: Int
    val histogram: HistogramIfc
    val breakPoints: DoubleArray
    val binProbabilities: DoubleArray
    val expectedCounts: DoubleArray
    val binCounts: DoubleArray
    val dof: Int
    val chiSquaredTestStatistic: Double
    val chiSquaredPValue: Double

    fun chiSquaredTestResults(type1Error: Double = 0.05): String {
        require((0.0 < type1Error) && (type1Error < 1.0)) { "Type 1 error must be in (0,1)" }
        val sb = StringBuilder()
        sb.appendLine("Chi-Squared Test Results:")
        sb.append(String.format("%-25s %-10s %10s %10s", "Bin Label", "P(Bin)", "Observed", "Expected"))
        sb.appendLine()
        for ((i, bin) in histogram.bins.withIndex()) {
            val o = bin.count
            val e = expectedCounts[i]
            val p = binProbabilities[i]
            val s = String.format("%-25s %-10f %10d %10f", bin.binLabel, p, o, e)
            sb.append(s)
            if (e <= 5){
                sb.append("\t *** Warning: expected <= 5 ***")
            }
            sb.appendLine()
        }
        sb.appendLine()
        sb.appendLine("Number of estimate parameters = $numEstimatedParameters")
        sb.appendLine("Number of intervals = ${histogram.numberBins}")
        sb.appendLine("Degrees of Freedom = $dof")
        sb.appendLine("Chi-Squared Test Statistic = $chiSquaredTestStatistic")
        sb.appendLine("P-value = $chiSquaredPValue")
        sb.appendLine("Hypothesis test at $type1Error level: ")
        if (chiSquaredPValue >= type1Error){
            sb.appendLine("The p-value = $chiSquaredPValue is >= $type1Error : Do not reject hypothesis.")
        } else {
            sb.appendLine("The p-value = $chiSquaredPValue is < $type1Error : Reject the null hypothesis")
        }
        return sb.toString()
    }
}