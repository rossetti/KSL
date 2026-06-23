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

import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trips an [AnalysisDocument] through TOML and confirms the document is
 * reference-based: it carries file/table/generated references, never embedded
 * dataset values.
 */
class AnalysisDocumentTomlTest {

    private fun document(): AnalysisDocument = AnalysisDocument(
        analysisName = "study",
        datasets = listOf(
            AnalysisDatasetEntry(
                NamedFitConfiguration(
                    name = "expo",
                    config = FitConfiguration(
                        dataSource = DataSourceReference.Generated(
                            rv = RVData(RVType.Exponential, mapOf("mean" to doubleArrayOf(10.0))),
                            sampleSize = 200, streamNumber = 1, name = "expo"
                        ),
                        bootstrap = BootstrapConfig(sampleSize = 399, level = 0.95, streamNumber = 1)
                    )
                ),
                included = true
            ),
            AnalysisDatasetEntry(
                NamedFitConfiguration(
                    name = "fromFile",
                    config = FitConfiguration(
                        dataSource = DataSourceReference.DelimitedFile(
                            path = "/data/x.csv", delimiter = Delimiter.COMMA, hasHeader = true,
                            layout = DatasetLayout.WIDE, datasetColumns = listOf("a")
                        )
                    )
                ),
                included = false
            ),
            AnalysisDatasetEntry(
                NamedFitConfiguration(
                    name = "fromDb",
                    config = FitConfiguration(
                        dataSource = DataSourceReference.Database(
                            connection = DatabaseConnectionRef(dbType = DbType.SQLITE, location = "/data/x.db"),
                            source = DbSource.Table("t"), layout = DatasetLayout.WIDE,
                            datasetColumns = listOf("b")
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `AnalysisDocument round-trips through TOML`() {
        val doc = document()
        val text = AnalysisDocumentToml.encode(doc)
        val decoded = AnalysisDocumentToml.decode(text)
        // RVData carries Map<String, DoubleArray>, which breaks data-class equals
        // (DoubleArray uses identity). A faithful codec re-encodes to identical TOML.
        assertEquals(text, AnalysisDocumentToml.encode(decoded))
        // Structural spot-checks.
        assertEquals("study", decoded.analysisName)
        assertEquals(listOf("expo", "fromFile", "fromDb"), decoded.datasets.map { it.config.name })
        assertEquals(listOf(true, false, true), decoded.datasets.map { it.included })
    }

    @Test
    fun `encoded TOML is reference-based and editable`() {
        val text = AnalysisDocumentToml.encode(document())
        // References are present...
        assertTrue(text.contains("study"))
        assertTrue(text.contains("/data/x.csv"))
        assertTrue(text.contains("/data/x.db"))
        // ...and there is no embedded inline dataset payload.
        assertFalse(text.contains("Inline"), "document must not embed inline data")
    }
}
