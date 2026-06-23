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

import net.peanuuutz.tomlkt.Toml

/**
 * TOML codec for [AnalysisDocument]. Uses `tomlkt` (which integrates with
 * `kotlinx-serialization`), mirroring `RunConfigurationToml`. Because the
 * document is reference-based — every dataset's `dataSource` is a file/table/
 * generated-RV reference, never inline data — the encoded TOML is compact and
 * hand-editable.
 */
object AnalysisDocumentToml {

    /** `explicitNulls = false` so null fields (e.g. an absent bootstrap) are omitted, keeping the file tidy. */
    private val myToml = Toml {
        explicitNulls = false
    }

    /** Serializes [doc] to a TOML string, prefixed with [DOCUMENT_HEADER]. */
    fun encode(doc: AnalysisDocument): String =
        DOCUMENT_HEADER + myToml.encodeToString(AnalysisDocument.serializer(), doc)

    /** Deserializes an [AnalysisDocument] from a TOML string produced by [encode]. */
    fun decode(text: String): AnalysisDocument =
        myToml.decodeFromString(AnalysisDocument.serializer(), text)

    private val DOCUMENT_HEADER: String = """
        # ────────────────────────────────────────────────────────────────────────────
        #  KSL Distribution-Fitting Analysis
        # ────────────────────────────────────────────────────────────────────────────
        #
        #  This file describes one distribution-fitting analysis for the KSL
        #  Distribution app. It is written and read by the app and can also be
        #  edited by hand.
        #
        #  It is reference-based: each dataset under [[datasets]] identifies its
        #  data by a `dataSource` reference (a delimited file, a database table,
        #  or a generated random variable) — never by embedded values. Data that
        #  was pasted into the app is spilled to a sidecar file in the analysis
        #  folder and referenced here like any other file.
        #
        #  Layout:
        #    analysisName        The analysis name.
        #    [[datasets]]        One entry per dataset: its fit configuration
        #                        (name, dataSource reference, kind, estimators,
        #                        scoring, ranking/evaluation, bootstrap) plus an
        #                        `included` flag for whether it participates in a run.
        #
        #  Editing guidelines:
        #   * String values use "double quotes"; numbers/booleans are bare literals.
        #   * A dataSource's `type` selects the reference kind (e.g. "delimitedFile",
        #     "database", "generated"); edit the path/table/parameters beneath it.
        #   * Saving from the app overwrites this file — hand-edited comments are not
        #     preserved; field values are.
        #
        #  Reference: https://rossetti.github.io/KSLBook/
        #
        # ────────────────────────────────────────────────────────────────────────────

        """.trimIndent()
}
