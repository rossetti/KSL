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

import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.mc1DIntegration
import ksl.utilities.io.report.extensions.mcExperiment
import ksl.utilities.io.report.extensions.mcExperimentConfig
import ksl.utilities.io.report.extensions.mcExperimentDiagnostics
import ksl.utilities.io.report.extensions.mcMultiVariateIntegration
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.math.FunctionIfc
import ksl.utilities.mcintegration.MC1DIntegration
import ksl.utilities.mcintegration.MCExperiment
import ksl.utilities.mcintegration.MCMultiVariateIntegration
import ksl.utilities.mcintegration.MCReplicationIfc
import ksl.utilities.random.mcmc.FunctionMVIfc
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.MVIndependentRV
import ksl.utilities.random.rvariable.UniformRV
import kotlin.math.sin

/**
 * Demonstrates use of the MCExperiment reporting framework extensions.
 *
 * Four demos:
 * 1. [demoMCExperimentBase]      — News Vendor profit via [MCExperiment] + [MCReplicationIfc];
 *                                  zero-code [toReport] path
 * 2. [demoMC1DIntegration]       — Sine-function integration via [MC1DIntegration];
 *                                  convergence history enabled, convergence plot rendered
 * 3. [demoMCMultiVariate]        — 2-D function via [MCMultiVariateIntegration];
 *                                  shows dimension in problem-setup section
 * 4. [demoMCCustomBlock]         — custom DSL block: configuration + diagnostics only,
 *                                  no statistics section; illustrates composability
 */
fun main() {
//    demoMCExperimentBase()
//    demoMC1DIntegration()
    demoMCMultiVariate()
//    demoMCCustomBlock()
}

// ── Demo 1: Base MCExperiment — News Vendor ───────────────────────────────────

/**
 * News Vendor problem: estimate expected profit for a perishable product when
 * demand follows an empirical discrete distribution.
 *
 * Demonstrates [MCExperiment.toReport] as a zero-code entry point for a plain
 * [MCExperiment] configured via a [MCReplicationIfc] lambda.
 *
 * **Illustrates:**
 * - Configuring [MCExperiment.desiredHWErrorBound] and [MCExperiment.maxSampleSize]
 * - Zero-code `exp.toReport().showInBrowser()` path
 * - Configuration and convergence-diagnostics sections for a base experiment
 */
fun demoMCExperimentBase() {
    println("=== Demo 1: Base MCExperiment — News Vendor ===")

    // Empirical demand distribution (values, CDF)
    val myValues = doubleArrayOf(5.0, 10.0, 40.0, 45.0, 50.0, 55.0, 60.0)
    val myCDF    = doubleArrayOf(0.1,  0.3,  0.6,  0.8,  0.9,  0.95, 1.0)
    val myDemand = DEmpiricalRV(myValues, myCDF, streamNum = 3)

    val myOrderQty    = 30.0
    val mySalesPrice  = 0.25
    val myUnitCost    = 0.15
    val mySalvage     = 0.02

    val myReplication = MCReplicationIfc { _ ->
        val d       = myDemand.value
        val sold    = minOf(d, myOrderQty)
        val leftOver = maxOf(0.0, myOrderQty - d)
        mySalesPrice * sold + mySalvage * leftOver - myUnitCost * myOrderQty
    }

    val myExp = MCExperiment(myReplication)
    myExp.desiredHWErrorBound = 0.01
    myExp.maxSampleSize       = 50000

    println("Running simulation…")
    myExp.runSimulation()
    println(myExp)

    myExp.toReport("News Vendor — Expected Profit").showInBrowser()
}

// ── Demo 2: MC1DIntegration — Sine integral with convergence history ──────────

/**
 * Evaluates ∫₀^π sin(x) dx = 2 via [MC1DIntegration].
 *
 * The sampler is Uniform(0, π) with density f(x) = 1/π. The function supplied to
 * [MC1DIntegration] must be h(x) = g(x)/f(x) where g(x) is the integrand.
 * Here g(x) = sin(x) and f(x) = 1/π, so h(x) = π·sin(x). The MC estimator then
 * converges to E_f[h(X)] = ∫₀^π π·sin(x)·(1/π) dx = ∫₀^π sin(x) dx = 2.
 *
 * Convergence history is enabled so the report includes a half-width convergence
 * plot showing adaptive sampling progress.
 *
 * **Illustrates:**
 * - [MC1DIntegration.toReport] zero-code path
 * - Problem-setup section showing function class, sampler class, and antithetic flag
 * - Enabling [MCExperiment.saveConvergenceHistory] to produce a convergence plot
 */
fun demoMC1DIntegration() {
    println("\n=== Demo 2: MC1DIntegration — Sine Integral ===")

    // g(x) = sin(x); sampler Uniform(0,π) ⟹ f(x) = 1/π ⟹ h(x) = g(x)/f(x) = π·sin(x)
    // E_f[h(X)] = ∫₀^π π·sin(x)·(1/π) dx = ∫₀^π sin(x) dx = 2 (exact)
    val mySineFunc = FunctionIfc { x -> Math.PI * sin(x) }
    val mySampler  = UniformRV(0.0, Math.PI, streamNum = 3)

    val myMc = MC1DIntegration(mySineFunc, mySampler)
    myMc.desiredHWErrorBound   = 0.001
    myMc.saveConvergenceHistory = true   // ← enables convergence plot in report

    println("Running simulation…")
    myMc.runSimulation()
    println(myMc)
    println("History points collected: ${myMc.convergenceHistory?.size ?: 0}")

    myMc.toReport("Sine Integral — MC 1D Integration").showInBrowser()
    myMc.toReport("Sine Integral — MC 1D Integration").writeHtml()
}

// ── Demo 3: MCMultiVariateIntegration — 2-D function ─────────────────────────

/**
 * Evaluates ∫₀¹∫₀¹ (4x²y + y²) dx dy = 1 via [MCMultiVariateIntegration].
 *
 * Sampler is bivariate Uniform(0,1)² with joint density w(x,y) = 1, so h(x,y) = g(x,y).
 * Exact decomposition:
 *   ∫₀¹∫₀¹ 4x²y dx dy = 4·(∫₀¹ x² dx)·(∫₀¹ y dy) = 4·(1/3)·(1/2) = 2/3
 *   ∫₀¹∫₀¹ y²   dx dy = (∫₀¹ dx)·(∫₀¹ y² dy)    =    1 · (1/3)   = 1/3
 *   Total = 2/3 + 1/3 = 1 (exact)
 *
 * Demonstrates [MCMultiVariateIntegration.toReport] and the dimension field
 * shown in the problem-setup section.
 *
 * **Illustrates:**
 * - [MCMultiVariateIntegration.toReport] zero-code path
 * - Problem-setup section with Dimension row
 * - Antithetic sampling (default on) for variance reduction
 */
fun demoMCMultiVariate() {
    println("\n=== Demo 3: MCMultiVariateIntegration — 2D Function ===")

    // g(x,y) = 4x²y + y²; sampler Uniform(0,1)² ⟹ w(x,y) = 1 ⟹ h(x,y) = g(x,y)
    // E_w[h(X,Y)] = ∫₀¹∫₀¹ (4x²y + y²) dx dy = 2/3 + 1/3 = 1 (exact)
    val myFunc = object : FunctionMVIfc {
        override val dimension: Int = 2
        override fun f(x: DoubleArray): Double {
            require(x.size == dimension)
            return 4.0 * x[0] * x[0] * x[1] + x[1] * x[1]
        }
    }
    val mySampler = MVIndependentRV(2, UniformRV(0.0, 1.0, streamNum = 5))

    val myMc = MCMultiVariateIntegration(myFunc, mySampler)
    myMc.desiredHWErrorBound    = 0.001
    myMc.saveConvergenceHistory = true

    println("Running simulation…")
    myMc.runSimulation()
    println(myMc)

    myMc.toReport("2D Integral — MC Multivariate Integration").showInBrowser()
}

// ── Demo 4: Custom DSL block ──────────────────────────────────────────────────

/**
 * Custom DSL block that produces a report containing only the configuration and
 * convergence-diagnostics sections, omitting the full statistics property table.
 *
 * Uses the granular [mcExperimentConfig] and [mcExperimentDiagnostics] building blocks
 * directly instead of the composite [mc1DIntegration] function, which would include
 * the statistics property table. A plain `paragraph` is appended with the estimated
 * value and half-width formatted inline.
 *
 * The function h(x) = π·sin(x) with sampler Uniform(0, π) estimates ∫₀^π sin(x) dx = 2:
 * E_f[h(X)] = ∫₀^π π·sin(x)·(1/π) dx = 2.
 *
 * **Illustrates:**
 * - Calling granular DSL functions ([mcExperimentConfig], [mcExperimentDiagnostics])
 *   to deliberately omit the `StatPropertyTable` section
 * - Appending a narrative `paragraph` after the diagnostic sections
 */
fun demoMCCustomBlock() {
    println("\n=== Demo 4: Custom DSL Block ===")

    val mySineFunc = FunctionIfc { x -> Math.PI * sin(x) }
    val mySampler  = UniformRV(0.0, Math.PI, streamNum = 7)

    val myMc = MC1DIntegration(mySineFunc, mySampler)
    myMc.desiredHWErrorBound = 0.001

    println("Running simulation…")
    myMc.runSimulation()

    // Custom block: configuration + diagnostics only (statistics property table omitted)
    val myDoc = report("Sine Integral — Custom Report") {
        mcExperimentConfig(myMc, caption = "Sine Integral Configuration")
        mcExperimentDiagnostics(myMc, caption = "Convergence Diagnostics")
        paragraph(
            "The exact value of ∫₀^π sin(x) dx = 2. " +
            "Estimated value: ${"%.4f".format(myMc.statistics().average)} " +
            "(half-width = ${"%.4f".format(myMc.statistics().halfWidth)})."
        )
    }
    myDoc.showInBrowser()
}
