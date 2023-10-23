/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.book.appendixB

import ksl.utilities.distributions.fitting.*
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.plotting.PMFComparisonPlot
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.toDoubles
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.annotations.DataSchema
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.io.ColType
import org.jetbrains.kotlinx.dataframe.io.readCSV
import java.nio.file.Path
import java.nio.file.Paths

class ExampleB1 {
}

@DataSchema
interface Observation {
    val week: Int
    val period: Int
    val day: String
    val count: Int
}

fun main() {
    val path = Paths.get("/Users/rossetti/Documents/GitHub/KSL/KSLExamples/chapterFiles/Appendix-Distribution Fitting/PoissonCountData.csv")
//    val file = KSLFileUtil.chooseFile()
    val file = path.toFile()
    val df = DataFrame.readCSV(file!!,
        colTypes = mapOf(
            "week" to ColType.Int,
            "period" to ColType.Int,
            "day" to ColType.String,
            "count" to ColType.Int
        )
    ).cast<Observation>()

    val count by column<Int>()
    val week by column<Int>()
    val period by column<Int>()
    val day by column<String>()

    val countData: DataColumn<Int> = df[count]

    println(df.schema())
    println()

    println(df)

    println()
    println(countData)

    val data = countData.toIntArray()

    val f = IntegerFrequency(data)
    val fp = f.frequencyPlot()
    fp.showInBrowser()

    val op = ObservationsPlot(data)
    op.showInBrowser()

    val acf = ACFPlot(data.toDoubles())
    acf.showInBrowser()

    println()
    println(f)

    val pmfModeler = PMFModeler(data)

    val results = pmfModeler.estimateParameters(setOf(PoissonMLEParameterEstimator))
    println()

    val e = results.first()
    println(e)
    val mean = e.parameters!!.doubleParameter("mean")

    val pf = PoissonGoodnessOfFit(data.toDoubles(), mean = mean)
    println()
    println(pf)

    val plot = PMFComparisonPlot(data, pf.distribution)
    plot.showInBrowser()
}