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

package ksl.app.swing.dist.panel

import ksl.app.dist.reporting.toCanonicalDocument
import ksl.app.dist.reporting.toDocument
import ksl.app.dist.result.BatchFitResultData
import ksl.app.dist.result.FitResultData
import ksl.utilities.io.report.writeHtml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Renders fit results to HTML and writes them into the analysis/dataset folders.
 * The caller opens the returned file in the system browser.
 *
 * A co-located client that holds the dataset's raw observations renders the
 * canonical engine report locally (`FitResultData.toCanonicalDocument`) — the
 * same report a live PDFModeler/PMFModeler produces. A remote client without raw
 * data falls back to the server-rendered `standardReportHtml`, and finally to the
 * plot-free DTO table report.
 */
object FitReports {

    fun single(result: FitResultData, rawData: DoubleArray?, dir: Path): Path {
        val file = dir.resolve("fit-report.html")
        return when {
            // Co-located: render the canonical report from the DTO + raw data.
            rawData != null ->
                result.toCanonicalDocument(rawData, title = result.datasetName).writeHtml(path = file).toPath()
            // Remote/thin client: use the server-rendered HTML when present.
            result.standardReportHtml != null -> {
                Files.writeString(file, result.standardReportHtml!!)
                file
            }
            // Last resort: plot-free DTO table report.
            else ->
                result.toDocument(title = result.datasetName).writeHtml(path = file).toPath()
        }
    }

    fun batchSummary(batch: BatchFitResultData, title: String, dir: Path): Path =
        batch.toDocument(title = title, includePerDataset = false)
            .writeHtml(path = dir.resolve("batch-report.html")).toPath()
}
