package ksl.examples.general.lectures.week2

import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.statistic.CachedHistogram

fun main() {
    // select file: drill2Data.txt
    val myFile = KSLFileUtil.chooseFile()
    if (myFile != null) {
        val data = KSLFileUtil.scanToArray(myFile.toPath())
        val ch = CachedHistogram(data)
        ch.currentHistogram.histogramPlot().showInBrowser()
        nonSplitAnalysis(data)
        //Try split values of (70, 73, 75, 78, 80) and make a recommendation.
        splitAnalysis(data, 80.0)
    }
}

fun nonSplitAnalysis(data: DoubleArray) {
    val analysis = PDFModeler(data)
    analysis.showAllResultsInBrowser()
}

fun splitAnalysis(data: DoubleArray, splitCriteria: Double) {
    val (bottom, top) = data.partition { it <= splitCriteria }
    val bottomAnalysis = PDFModeler(bottom.toDoubleArray())
    bottomAnalysis.showAllResultsInBrowser()
    val topAnalysis = PDFModeler(top.toDoubleArray())
    topAnalysis.showAllResultsInBrowser()
}