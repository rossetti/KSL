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

import ksl.app.dist.reporting.toDocument
import ksl.app.dist.result.BatchFitResultData
import ksl.app.dist.result.FitResultData
import ksl.utilities.io.report.writeHtml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Renders fit results to HTML using the substrate's own report builders
 * (`FitResultData.toDocument` / `BatchFitResultData.toDocument`) and writes them
 * into the analysis/dataset folders. The caller opens the returned file in the
 * system browser. Per-dataset reports take the dataset's raw observations so the
 * fit-quality plots reconstruct.
 */
object FitReports {

    fun single(result: FitResultData, rawData: DoubleArray?, dir: Path): Path {
        val file = dir.resolve("fit-report.html")
        val canonical = result.standardReportHtml
        return if (canonical != null) {
            Files.writeString(file, canonical)
            file
        } else {
            // Fallback to the DTO-driven report when the canonical render is absent.
            result.toDocument(rawData = rawData, title = result.datasetName).writeHtml(path = file).toPath()
        }
    }

    fun batchSummary(batch: BatchFitResultData, title: String, dir: Path): Path =
        batch.toDocument(title = title, includePerDataset = false)
            .writeHtml(path = dir.resolve("batch-report.html")).toPath()
}
