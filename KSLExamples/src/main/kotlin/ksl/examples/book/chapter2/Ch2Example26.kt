/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.book.chapter2

import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.distributions.fitting.*
import ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.PoissonMLEParameterEstimator
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.plotting.PMFComparisonPlot
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.BootstrapSampler
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.toDoubles
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.column
import org.jetbrains.kotlinx.dataframe.api.toIntArray
import org.jetbrains.kotlinx.dataframe.io.ColType
import org.jetbrains.kotlinx.dataframe.io.readCSV
import org.jetbrains.kotlinx.dataframe.io.readCsv

/**
 *  Example 2.26
 *  Illustrates how to fit a Poisson distribution using KSL constructs.
 */
fun main() {
    val data = ksl.examples.book.appendixB.readCountData()
    val f = IntegerFrequency(data)
    val fp = f.frequencyPlot()
    fp.showInBrowser()
    fp.saveToFile("Lab_Count_Freq_Plot")
    val op = ObservationsPlot(data)
    op.saveToFile("Lab_Count_Obs_Plot")
    op.showInBrowser()
    val acf = ACFPlot(data.toDoubles())
    acf.saveToFile("Lab_Count_ACF_Plot")
    acf.showInBrowser()
    println(f)
    val pmfModeler = PMFModeler(data)
    val results = pmfModeler.estimateParameters(setOf(PoissonMLEParameterEstimator))
    val e = results.first()
    println(e)
    val mean = e.parameters!!.doubleParameter("mean")
    val pf = PoissonGoodnessOfFit(data.toDoubles(), mean = mean)
    println(pf)
    val plot = PMFComparisonPlot(data, pf.distribution)
    plot.saveToFile("Lab_Count_PMF_Plot")
    plot.showInBrowser()
}

/**
 *  This function was written to import the data from the csv file for the example
 *  as a dataframe.
 */
fun readCountData(): IntArray {
    // choose file: KSL/KSLExamples/chapterFiles/Appendix-Distribution Fitting/PoissonCountData.csv
    val file = KSLFileUtil.chooseFile()
    if (file != null) {
        val df = DataFrame.readCsv(
            file = file,
            delimiter = ',', header = listOf(), colTypes = mapOf(
                "week" to ColType.Int,
                "period" to ColType.Int,
                "day" to ColType.String,
                "count" to ColType.Int
            ),
            skipLines = 0, readLines = null, allowMissingColumns = true, parserOptions = null
        )
        val count by column<Int>()
        val countData: DataColumn<Int> = df.get { count}
        return countData.toIntArray()
    }
    return IntArray(0)
}