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
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.ObservationsPlot

fun main(){
    // select file: PharmacyInputModelingExampleData.txt
    val myFile = KSLFileUtil.chooseFile()!!
    val data = KSLFileUtil.scanToArray(myFile.toPath())
    val d = PDFModeler(data)

    println(d.histogram)
    println()

//    val hPlot = d.histogram.histogramPlot()
//    hPlot.showInBrowser()
//
//    val op = ObservationsPlot(data)
//    op.showInBrowser()
//
//    val acf = ACFPlot(data)
//    acf.showInBrowser()

    val estimationResults  = d.estimateParameters(ExponentialMLEParameterEstimator)
    val estimationResult = estimationResults.first()
    println(estimationResult)
    println()

    val results = d.evaluateScores(estimationResults)

    results.sortedScoringResults.forEach(::println)

    val topResult = results.sortedScoringResults.first()
 //   topResult.distributionFitPlot().showInBrowser("Recommended Distribution ${topResult.name}")
    println()
    println("Recommended Distribution ${topResult.name}")
    println()

    println(topResult.distribution)
    println()

    val gof = ContinuousCDFGoodnessOfFit(data,
        topResult.distribution,
        numEstimatedParameters = topResult.numberOfParameters
    )
    println(gof)
}