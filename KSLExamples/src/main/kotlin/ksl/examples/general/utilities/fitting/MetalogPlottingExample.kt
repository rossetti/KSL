/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.utilities.fitting

import ksl.utilities.distributions.metalog.MetalogDistribution
import ksl.utilities.distributions.fitting.ContinuousCDFGoodnessOfFit
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.random.rvariable.LognormalRV

/**
 *  Viewing metalog fitting results through the PDFModeler plotting and reporting facilities.
 *
 *  This is separate from [MetalogFittingExample] on purpose. That example is written to run
 *  unattended: it generates its own data and prints to the console, opening nothing. Everything
 *  here opens browser windows or writes image files, so running it is a deliberate act rather
 *  than something that happens as a side effect of running the other example.
 *
 *  There is nothing metalog-specific to arrange. A metalog is a `ContinuousDistributionIfc` and an
 *  `InverseCDFIfc` like any other fitted distribution, which is exactly what the quad plot needs:
 *  a density plot from `pdf`, an empirical CDF overlay from `cdf`, a Q-Q plot from `invCDF`, and a
 *  P-P plot from `cdf`. The semi-bounded members report an infinite bound, and that flows through
 *  the plotting and goodness-of-fit code without special handling.
 *
 *  Requires a desktop capable of opening a browser: running `main` opens six tabs, four from
 *  the full report and two comparing individual candidates. Image files are written to
 *  `KSL.plotDir`. In a strictly headless environment the browser call raises `HeadlessException`
 *  and the file-writing call fails to initialize the plotting library; neither is metalog-specific,
 *  and a lognormal fit behaves identically.
 */
fun main() {
    // Positive, right-skewed data, so the lower-bounded members of the family are in their element.
    val data = LognormalRV(mean = 10.0, variance = 25.0, streamNum = 1).sample(500)

    showEveryResultInABrowser(data)
    println()
    plotTheRecommendedDistribution(data)
    println()
    saveTheQuadPlotToAFile(data)
}

/**
 *  The single call that produces the whole visual report.
 *
 *  `showAllResultsInBrowser` opens four pages, and it is worth knowing what each one is before
 *  four tabs appear:
 *
 *  1. Statistical summary — descriptive statistics of the data. Nothing to do with the fits.
 *  2. Visualization summary — histogram, observation plot, and autocorrelation plot of the data.
 *     Also about the data rather than the fits; the autocorrelation plot is the one to read first,
 *     since fitting a distribution to correlated observations is a mistake no goodness-of-fit
 *     statistic will report.
 *  3. Scoring summary — the MODA tables: raw scores per metric, the values those transform to,
 *     the ranks, and the overall weighted value that orders the candidates.
 *  4. Goodness-of-fit summary — for the recommended distribution, the four-panel diagnostic plot
 *     together with the chi-squared and Kolmogorov-Smirnov results.
 */
private fun showEveryResultInABrowser(data: DoubleArray) {
    println("Opening the full result set in a browser")
    println("=".repeat(70))

    val modeler = PDFModeler(data)
    // Shifting is off because every estimator here declines it. See MetalogParameterEstimator.
    val results = modeler.showAllResultsInBrowser(
        estimators = PDFModeler.metalogEstimators,
        automaticShifting = false
    )

    val top = results.resultsSortedByScoring.first()
    println("recommended: ${top.name}")
    println("             ${top.distribution}")
    println()
    println(
        """
        Four browser tabs were opened. Read the autocorrelation plot in the visualization summary
        before anything else: if the observations are correlated, none of the fits mean what they
        appear to mean, and no goodness-of-fit statistic on this page will tell you so.

        In the goodness-of-fit summary, the four panels answer different questions. The density
        overlay shows whether the shape is right. The Q-Q plot is the one to trust for the tails,
        because it compares quantiles directly and a discrepancy out there is visible rather than
        squeezed against the axis. The P-P plot is the opposite: it is most sensitive in the body
        of the distribution and compresses the tails. The empirical CDF overlay is the easiest to
        read but hides small differences.

        A metalog will usually look excellent on all four. That is what a flexible family does,
        and it is not by itself an argument for using one. See the closing discussion in
        MetalogFittingExample.
        """.trimIndent()
    )
}

/**
 *  The per-distribution quad plot, for a distribution you choose rather than the recommended one.
 *
 *  Every scoring result can produce its own plot, so any candidate can be inspected, not only the
 *  winner. This is more useful than it sounds for the metalog, where several candidates typically
 *  score within a hair of each other and the interesting question is whether they actually differ
 *  anywhere a modeller cares about.
 */
private fun plotTheRecommendedDistribution(data: DoubleArray) {
    println("Plotting individual candidates")
    println("=".repeat(70))

    val results = PDFModeler(data).estimateAndEvaluateScores(
        PDFModeler.metalogEstimators, automaticShifting = false
    )
    val ranked = results.resultsSortedByScoring

    // The best fit, and the best one with a different number of terms, to see whether the extra
    // terms bought anything visible.
    val best = ranked.first()
    val bestTerms = (best.distribution as MetalogDistribution).numTerms
    val different = ranked.firstOrNull { (it.distribution as MetalogDistribution).numTerms != bestTerms }

    println("best            : ${best.name}  ${best.distribution}")
    best.distributionFitPlot().showInBrowser("Best ${best.name}")
    if (different != null) {
        println("different arity : ${different.name}  ${different.distribution}")
        different.distributionFitPlot().showInBrowser("Alternative ${different.name}")
    }

    // The goodness-of-fit numbers behind the plots, printed rather than rendered. ScoringResult
    // also offers displayFittingResults(), which prints these *and* opens the plot in a browser;
    // it is not used here because the plot is already open above and one window is enough.
    println()
    val gof = ContinuousCDFGoodnessOfFit(
        best.estimationResult.testData,
        best.distribution,
        numEstimatedParameters = best.numberOfParameters
    )
    println(gof)
    println(
        """
        A note on the degrees of freedom above. The estimated-parameter count is the estimator's
        own, not the size of the distribution type's declared parameter set, and for a metalog the
        two differ. Boundedness is derived from the bound values rather than being part of the
        type, so every metalog type declares both bounds: a four-term unbounded fit reports four
        parameters against a declared six. The count subtracted here is therefore the number
        actually estimated, which is what the chi-squared degrees of freedom need.

        One imprecision remains. An estimator handed a bound rather than fitting it still names
        that bound, so a metalog fitted with a supplied bound counts it as estimated.
        """.trimIndent()
    )
}

/**
 *  Writing the quad plot to an image file instead of opening a window, which is what a batch or
 *  headless context needs. The file lands in `KSL.plotDir` unless a directory is supplied.
 */
private fun saveTheQuadPlotToAFile(data: DoubleArray) {
    println("Saving a plot to a file")
    println("=".repeat(70))

    val results = PDFModeler(data).estimateAndEvaluateScores(
        PDFModeler.metalogEstimators, automaticShifting = false
    )
    val top = results.resultsSortedByScoring.first()
    val file = top.distributionFitPlot().saveToFile("Metalog_Fit_Quad_Plot")
    println("wrote ${file.absolutePath}")
    println()
    println(
        """
        Rendering needs a graphics stack even when nothing is displayed, so this call fails in a
        strictly headless environment. That is a property of the plotting library rather than of
        the metalog: a lognormal fit fails the same way in the same environment. The scoring and
        goodness-of-fit computations themselves have no such requirement, which is why
        MetalogFittingExample can print its rankings anywhere.
        """.trimIndent()
    )
}
