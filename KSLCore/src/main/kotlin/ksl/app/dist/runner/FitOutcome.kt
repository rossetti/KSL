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

package ksl.app.dist.runner

import ksl.app.dist.result.FitReport
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.PDFModelingResults

/**
 * In-process result of a single fit, carrying both the wire-safe
 * `FitReport` DTO and the live KSL modeling objects that produced it.
 *
 * This type is deliberately NOT `@Serializable`: the live `PDFModeler` and
 * `PDFModelingResults` cannot cross a process boundary, and they exist here
 * only so the in-process reporting layer can build a `ReportNode.Document`
 * via the existing `*.toReport()` extensions, which require the original
 * modeler (the results object holds no back-reference to it).
 *
 * The hierarchy is sealed with a single continuous variant for now; the
 * discrete (PMF) path adds its own variant carrying a `PMFModeler` and its
 * goodness-of-fit objects, mirroring the intentional PDF/PMF asymmetry.
 */
sealed class FitOutcome {

    /** The wire-safe machine result; always present regardless of variant. */
    abstract val report: FitReport

    /** The name of the dataset that was fit. */
    abstract val datasetName: String

    /**
     * Continuous fit outcome. Holds the live `PDFModeler` and
     * `PDFModelingResults` so the reporting layer can delegate to
     * `PDFModelingResults.toReport(modeler, ...)`.
     */
    class Continuous(
        override val report: FitReport,
        override val datasetName: String,
        val modeler: PDFModeler,
        val results: PDFModelingResults
    ) : FitOutcome()
}
