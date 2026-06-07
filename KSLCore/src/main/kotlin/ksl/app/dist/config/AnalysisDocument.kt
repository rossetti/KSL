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

package ksl.app.dist.config

import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/**
 * The persistable, hand-editable form of a distribution-fitting analysis. It is
 * a configuration of **references** — each dataset is identified by its
 * `DataSourceReference` (a file/table/generated-RV locator), never by embedded
 * data — so the saved TOML stays small and editable. (Inline-pasted data is
 * spilled to a sidecar file by the front-end before saving, and referenced like
 * any other file.) This mirrors how the Single/Scenario apps persist a
 * `RunConfiguration` that references a model bundle rather than embedding it.
 */
@Serializable
data class AnalysisDocument(
    @TomlComment("String. The analysis name; labels the document and names its output folder.")
    val analysisName: String,
    @TomlComment(
        "List of datasets in the analysis. Each entry carries its own fit\n" +
        "configuration plus an `included` flag for whether it participates in a run."
    )
    val datasets: List<AnalysisDatasetEntry>
)

/**
 * One dataset within an [AnalysisDocument]: the per-dataset fit configuration
 * (name + source reference + fit settings) plus whether it is included in a run.
 */
@Serializable
data class AnalysisDatasetEntry(
    @TomlComment("The per-dataset fit configuration: name, data-source reference, and fit settings.")
    val config: NamedFitConfiguration,
    @TomlComment("Boolean. When true the dataset participates in a run; when false it is kept but skipped.")
    val included: Boolean = true
)
