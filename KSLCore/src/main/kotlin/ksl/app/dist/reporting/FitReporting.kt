/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.app.dist.reporting

import ksl.app.dist.runner.FitOutcome
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.dataStatisticalSummary
import ksl.utilities.io.report.extensions.moda
import ksl.utilities.io.report.extensions.toReport

/**
 * Builds human-facing `ReportNode.Document`s from a fit outcome by
 * delegating to the existing `ksl.utilities.io.report` framework. The
 * substrate contributes no renderers and no report-node types of its own;
 * it only composes the framework's ready-made fitting sections.
 *
 * Two document shapes are offered because the framework's sections differ
 * sharply in cost:
 *
 *  - [fullDocument] is the complete report (data summary, visualization,
 *    MODA scoring, and goodness-of-fit). The goodness-of-fit section runs
 *    bootstrapping and embeds Lets-Plot figures, so it is comparatively
 *    expensive and — because of the bootstrap — not deterministic. Use it
 *    for the "generate the HTML report" action: render to HTML / Markdown
 *    file or open in a browser for full fidelity.
 *
 *  - [summaryDocument] is a lightweight, plot-free view (data summary plus
 *    the MODA scoring table). It produces no plot nodes, so rendering it to
 *    text or Markdown writes no image files and incurs no Lets-Plot cost.
 *    (It is not byte-deterministic across renders: the reused data-summary
 *    section bootstraps the minimum CI for the shift analysis.) Use it for
 *    an immediate in-app or console preview.
 *
 * Both currently handle the continuous path only; the discrete path adds
 * its own builders when the PMF outcome variant lands.
 */
object FitReporting {

    /**
     * Full continuous fitting report. Delegates entirely to the framework's
     * `PDFModelingResults.toReport(modeler, ...)` extension.
     *
     * @param outcome         the continuous fit outcome carrying the live modeler/results
     * @param title           document title
     * @param confidenceLevel confidence level for CIs and GoF tests; must be in (0, 1)
     * @param allGOF          when true, GoF is reported for every fitted distribution;
     *                        when false (default), only the top-ranked distribution's GoF
     */
    fun fullDocument(
        outcome: FitOutcome.Continuous,
        title: String = "Distribution Fitting — ${outcome.datasetName}",
        confidenceLevel: Double = 0.95,
        allGOF: Boolean = false
    ): ReportNode.Document =
        outcome.results.toReport(
            modeler = outcome.modeler,
            title = title,
            confidenceLevel = confidenceLevel,
            allGOF = allGOF
        )

    /**
     * Lightweight, plot-free summary: the data statistical summary plus the
     * MODA scoring table. Renders to text or Markdown with no plot image
     * files. (Not byte-deterministic across renders: the reused data-summary
     * section bootstraps the minimum CI for the shift analysis.)
     *
     * @param outcome         the continuous fit outcome carrying the live modeler/results
     * @param title           document title
     * @param confidenceLevel confidence level for the data summary; must be in (0, 1)
     */
    fun summaryDocument(
        outcome: FitOutcome.Continuous,
        title: String = "Distribution Fitting Summary — ${outcome.datasetName}",
        confidenceLevel: Double = 0.95
    ): ReportNode.Document = report(title) {
        dataStatisticalSummary(outcome.modeler, confidenceLevel = confidenceLevel)
        moda(outcome.results.evaluationModel, caption = "MODA Scoring Results")
    }
}
