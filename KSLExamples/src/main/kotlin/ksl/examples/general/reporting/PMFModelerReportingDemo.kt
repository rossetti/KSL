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

package ksl.examples.general.reporting

import ksl.utilities.distributions.DiscretePMFInRangeDistributionIfc
import ksl.utilities.distributions.fitting.DiscretePMFGoodnessOfFit
import ksl.utilities.distributions.fitting.PMFModeler
import ksl.utilities.distributions.fitting.PoissonGoodnessOfFit
import ksl.utilities.distributions.fitting.estimators.NegBinomialMOMParameterEstimator
import ksl.utilities.distributions.fitting.estimators.PoissonMLEParameterEstimator
import ksl.utilities.io.report.extensions.discreteDataSummary
import ksl.utilities.io.report.extensions.discreteGoodnessOfFit
import ksl.utilities.io.report.extensions.discreteVisualization
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.random.rvariable.NegativeBinomialRV
import ksl.utilities.random.rvariable.PoissonRV
import ksl.utilities.toDoubles

/**
 * Demonstrates use of the PMFModeler reporting framework extensions.
 *
 * Five demos:
 * 1. [demoPMFEdaOnly]             — zero-code EDA report from [PMFModeler.toReport]
 * 2. [demoPMFPoissonGOF]          — full Poisson GOF report via [DiscretePMFGoodnessOfFit.toReport]
 * 3. [demoPMFNegBinomialGOF]      — two-parameter NegBinomial GOF report
 * 4. [demoPMFCustomBlock]         — custom DSL block replacing the default
 * 5. [demoPMFGranularFunctions]   — individual functions composed into a single document
 */
fun main() {
    demoPMFEdaOnly()
//    demoPMFPoissonGOF()
//    demoPMFNegBinomialGOF()
//    demoPMFCustomBlock()
//    demoPMFGranularFunctions()
}

// ── Shared test data ──────────────────────────────────────────────────────────

/** 300 samples from Poisson(mean=5.0) */
private fun pmfPoissonData(): IntArray {
    val rv = PoissonRV(mean = 5.0, streamNum = 1)
    return IntArray(300) { rv.value.toInt() }
}

/** 300 samples from NegativeBinomial(probSuccess=0.3, numSuccess=4.0) */
private fun pmfNegBinomialData(): IntArray {
    val rv = NegativeBinomialRV(probOfSuccess = 0.3, numSuccess = 4.0, streamNum = 2)
    return IntArray(300) { rv.value.toInt() }
}

// ── Demo 1: EDA only ──────────────────────────────────────────────────────────

/**
 * Demo 1 — zero-code EDA report.
 *
 * [PMFModeler.toReport] produces a document containing the integer frequency
 * summary (frequency table, statistics, frequency bar chart), dispersion
 * analysis, and three exploratory plots (frequency, observations, ACF).
 * No fitted distribution is required.
 */
fun demoPMFEdaOnly() {
    val myData    = pmfPoissonData()
    val myModeler = PMFModeler(myData)

    println("=== Demo 1: EDA Only ===")
    println("Data summary: n=${myModeler.statistics.count.toInt()}, " +
            "mean=${"%.4f".format(myModeler.statistics.average)}, " +
            "hasZeroes=${myModeler.hasZeroes}")

    myModeler.toReport(title = "Poisson Count Data — EDA").showInBrowser()
}

// ── Demo 2: Full Poisson GOF report ──────────────────────────────────────────

/**
 * Demo 2 — estimate Poisson parameters, construct [PoissonGoodnessOfFit],
 * and render a full GOF report via [DiscretePMFGoodnessOfFit.toReport].
 *
 * The report contains:
 * - Data statistical summary (frequency distribution, dispersion analysis)
 * - Data visualization (frequency plot, observations, ACF)
 * - Chi-squared GOF test section (bin table, test summary, dispersion tests,
 *   PMF comparison plot)
 */
fun demoPMFPoissonGOF() {
    val myData    = pmfPoissonData()
    val myModeler = PMFModeler(myData)

    // Estimate Poisson mean via MLE
    val myResults = myModeler.estimateParameters(setOf(PoissonMLEParameterEstimator))
    val myResult  = myResults.first()
    println("=== Demo 2: Poisson GOF ===")
    println(myResult)

    val myMean = myResult.parameters!!.doubleParameter("mean")
    val myGof  = PoissonGoodnessOfFit(myData.toDoubles(), mean = myMean)

    println(myGof.chiSquaredTestResults())

    myGof.toReport(
        modeler = myModeler,
        title   = "Poisson Distribution Fit — Count Data"
    ).showInBrowser()
}

// ── Demo 3: NegBinomial GOF report ───────────────────────────────────────────

/**
 * Demo 3 — estimate NegBinomial parameters via method of moments, construct
 * [DiscretePMFGoodnessOfFit], and render the full GOF report.
 *
 * NegBinomial has two parameters so [numEstimatedParameters] = 2.
 */
fun demoPMFNegBinomialGOF() {
    val myData    = pmfNegBinomialData()
    val myModeler = PMFModeler(myData)

    // Estimate NegBinomial parameters via method of moments
    val myResults = myModeler.estimateParameters(setOf(NegBinomialMOMParameterEstimator))
    val myResult  = myResults.first()
    println("=== Demo 3: NegBinomial GOF ===")
    println(myResult)

    if (!myResult.success || myResult.parameters == null) {
        println("Estimation failed: ${myResult.message}")
        return
    }

    // createDistribution returns DiscreteDistributionIfc; cast to the interface
    // required by DiscretePMFGoodnessOfFit (all supported discrete types implement both)
    val myDist = PMFModeler.createDistribution(myResult.parameters!!)
                     as? DiscretePMFInRangeDistributionIfc
    if (myDist == null) {
        println("Could not create a DiscretePMFInRangeDistributionIfc from parameters.")
        return
    }

    val myBreakPoints = PMFModeler.makeZeroToInfinityBreakPoints(myData.size, myDist)
    val myGof = DiscretePMFGoodnessOfFit(
        data                   = myData.toDoubles(),
        distribution           = myDist,
        numEstimatedParameters = 2,
        breakPoints            = myBreakPoints
    )

    println(myGof.chiSquaredTestResults())

    myGof.toReport(
        modeler = myModeler,
        title   = "NegBinomial Distribution Fit"
    ).showInBrowser()
}

// ── Demo 4: Custom block ──────────────────────────────────────────────────────

/**
 * Demo 4 — custom DSL block that replaces the default report content.
 *
 * The custom block uses the captured local variables [myModeler] and [myGof]
 * (not `this@toReport` labels, which are not in scope at the call site).
 * An extra paragraph is appended after the GOF section.
 */
fun demoPMFCustomBlock() {
    val myData    = pmfPoissonData()
    val myModeler = PMFModeler(myData)

    val myResults = myModeler.estimateParameters(setOf(PoissonMLEParameterEstimator))
    val myMean    = myResults.first().parameters!!.doubleParameter("mean")
    val myGof     = PoissonGoodnessOfFit(myData.toDoubles(), mean = myMean)

    println("=== Demo 4: Custom Block ===")

    myGof.toReport(
        modeler = myModeler,
        title   = "Poisson Fit — Custom Report"
    ) {
        // Explicit summary only (omit visualization to keep the document shorter)
        discreteDataSummary(myModeler)
        discreteGoodnessOfFit(myGof, myModeler)
        paragraph(
            "The Poisson distribution is an appropriate model for these count data. " +
            "The estimated mean is ${"%.4f".format(myMean)}."
        )
    }.showInBrowser()
}

// ── Demo 5: Granular functions in a standalone document ───────────────────────

/**
 * Demo 5 — calls [discreteDataSummary], [discreteVisualization], and
 * [discreteGoodnessOfFit] individually via the [report] DSL, combining
 * both Poisson and NegBinomial fits in a single document.
 *
 * This illustrates how the granular functions can be assembled freely when the
 * zero-code [DiscretePMFGoodnessOfFit.toReport] entry point does not match
 * the desired layout.
 */
fun demoPMFGranularFunctions() {
    // ── Poisson data and fit ──────────────────────────────────────────────────
    val myPoissonData    = pmfPoissonData()
    val myPoissonModeler = PMFModeler(myPoissonData)

    val myPoissonResults = myPoissonModeler.estimateParameters(setOf(PoissonMLEParameterEstimator))
    val myMean           = myPoissonResults.first().parameters!!.doubleParameter("mean")
    val myPoissonGof     = PoissonGoodnessOfFit(myPoissonData.toDoubles(), mean = myMean)

    // ── NegBinomial data and fit ──────────────────────────────────────────────
    val myNbData    = pmfNegBinomialData()
    val myNbModeler = PMFModeler(myNbData)

    val myNbResults = myNbModeler.estimateParameters(setOf(NegBinomialMOMParameterEstimator))
    val myNbResult  = myNbResults.first()
    val myNbDist    = PMFModeler.createDistribution(myNbResult.parameters!!)
                         as DiscretePMFInRangeDistributionIfc
    val myNbBp      = PMFModeler.makeZeroToInfinityBreakPoints(myNbData.size, myNbDist)
    val myNbGof     = DiscretePMFGoodnessOfFit(myNbData.toDoubles(), myNbDist, 2, myNbBp)

    println("=== Demo 5: Granular Functions ===")

    val myDoc = report("Discrete Distribution Fitting — Comparative Study") {

        section("Poisson Analysis") {
            discreteDataSummary(myPoissonModeler)
            discreteVisualization(myPoissonModeler)
            discreteGoodnessOfFit(myPoissonGof, myPoissonModeler)
        }

        section("Negative Binomial Analysis") {
            discreteDataSummary(myNbModeler)
            discreteVisualization(myNbModeler)
            discreteGoodnessOfFit(myNbGof, myNbModeler)
        }
    }

    myDoc.showInBrowser()
    myDoc.writeHtml()
}
