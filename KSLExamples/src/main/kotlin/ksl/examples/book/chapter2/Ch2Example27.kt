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

/**
 *  Example 2.27
 *  Illustrates how to fit a continuous distribution using KSL constructs.
 */
fun main() {
//    browserResults()

//    scriptedResults()

    allGoodnessOfFitResults()
}

fun browserResults(){
    // select file: PharmacyInputModelingExampleData.txt
    val myFile = KSLFileUtil.chooseFile()
    if (myFile != null){
        val data = KSLFileUtil.scanToArray(myFile.toPath())
        val d = PDFModeler(data)
        d.showAllResultsInBrowser()
    }
}

fun allGoodnessOfFitResults(){
    // select file: PharmacyInputModelingExampleData.txt
    val myFile = KSLFileUtil.chooseFile()
    if (myFile != null){
        val data = KSLFileUtil.scanToArray(myFile.toPath())
        val d = PDFModeler(data)
        val results  = d.estimateAndEvaluateScores()
        d.showAllGoodnessOfFitSummariesInBrowser(results)
    }
}

/**
 *  Analyze the distribution based on its placement in the scoring
 *  place = 1 means first place (the default)
 *  place = 2 means second place, et.
 */
fun showGoodnessOfFitSummaryInBrowser(place: Int = 1,
        resultsFileName: String = "PDF_Modeling_Goodness_Of_Fit_Summary"){
    val myFile = KSLFileUtil.chooseFile()
    if (myFile != null){
        val data = KSLFileUtil.scanToArray(myFile.toPath())
        val d = PDFModeler(data)
        val results  = d.estimateAndEvaluateScores()
        val sortedResults: List<ScoringResult> = results.resultsSortedByScoring
        val result: ScoringResult = sortedResults[place-1]
        KSLFileUtil.openInBrowser(
            fileName = resultsFileName,
            d.htmlGoodnessOfFitSummary(result)
        )
    }
}

fun scriptedResults(){
    // select file: PharmacyInputModelingExampleData.txt
    val myFile = KSLFileUtil.chooseFile()
    if (myFile != null){
        val data = KSLFileUtil.scanToArray(myFile.toPath())
        val d = PDFModeler(data)
        println(d.histogram)
        println()
        val hPlot = d.histogram.histogramPlot()
        hPlot.showInBrowser()
        val op = ObservationsPlot(data)
        op.showInBrowser()
        val acf = ACFPlot(data)
        acf.showInBrowser()
        val results  = d.estimateAndEvaluateScores()
        println("PDF Estimation Results for each Distribution:")
        println("------------------------------------------------------")
        results.resultsSortedByScoring.forEach(::println)
        val topResult = results.topResultByScore
        val scores = results.scoresAsDataFrame()
        println()
        println(scores)
        val values = results.metricsAsDataFrame()
        println()
        println(values)
        val distPlot = topResult.distributionFitPlot()
        distPlot.showInBrowser("Recommended Distribution ${topResult.name}")
        println()
        println("** Recommended Distribution** ${topResult.name}")
        println()
        val gof = ContinuousCDFGoodnessOfFit(topResult.estimationResult.testData,
            topResult.distribution,
            numEstimatedParameters = topResult.numberOfParameters
        )
        println(gof)
    }
}

