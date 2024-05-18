package ksl.examples.general.utilities

import ksl.utilities.statistic.OLSRegression
import ksl.utilities.statistic.RegressionData
import ksl.utilities.transpose

fun main() {
    val y = doubleArrayOf(
        9.95, 24.45, 31.75, 35.0, 25.02, 16.86, 14.38, 9.6, 24.35, 27.5, 17.08, 37.0,
        41.95, 11.66, 21.65, 17.89, 69.0, 10.3, 34.93, 46.59, 44.88, 54.12, 56.63, 22.13, 21.15
    )
    val x1 = doubleArrayOf(
        2.0, 8.0, 11.0, 10.0, 8.0, 4.0, 2.0, 2.0, 9.0, 8.0, 4.0, 11.0, 12.0, 2.0, 4.0,
        4.0, 20.0, 1.0, 10.0, 15.0, 15.0, 16.0, 17.0, 6.0, 5.0
    )
    val x2 = doubleArrayOf(
        50.0, 110.0, 120.0, 550.0, 295.0, 200.0, 375.0, 52.0, 100.0, 300.0, 412.0,
        400.0, 500.0, 360.0, 205.0, 400.0, 600.0, 585.0, 540.0, 250.0, 290.0, 510.0, 590.0, 100.0, 400.0
    )
    val data = arrayOf(x1, x2).transpose()
    val rd = RegressionData(y, data)
//    val df = rd.toDataFrame()
//    println(df)
    println()
    val r1 = OLSRegression(rd)
    println(r1)
//    println()
//    println(r1.residualsAsDataFrame())

//      r1.standardizedResidualsNormalPlot().showInBrowser()
//      r1.residualsVsPredictedPlot().showInBrowser()

//    println()
//    println("Test data frame")
//    val regressionData = RegressionData.create(df, "Y", listOf("X_1", "X_2"))
//    val r2 = OLSRegression(regressionData)
//    println(r2)
//    println()

}