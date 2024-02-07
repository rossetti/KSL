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

import ksl.utilities.distributions.fitting.ContinuousCDFGoodnessOfFit
import ksl.utilities.distributions.fitting.ExponentialMLEParameterEstimator
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.asMarkDownTable
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.ObservationsPlot

fun main(){

    asBrowserResults()

 //   scriptedResults()

}

fun asBrowserResults(){
    // select file: PharmacyInputModelingExampleData.txt
    val myFile = KSLFileUtil.chooseFile()
    if (myFile != null){
        val data = KSLFileUtil.scanToArray(myFile.toPath())
        val d = PDFModeler(data)
        d.showAllResultsInBrowser()
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
        results.sortedScoringResults.forEach(::println)

        val topResult = results.sortedScoringResults.first()
        val scores = results.evaluationModel.alternativeScoresAsDataFrame("Distributions")
        println()
        println(scores)
        val values = results.evaluationModel.alternativeValuesAsDataFrame("Distributions")
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