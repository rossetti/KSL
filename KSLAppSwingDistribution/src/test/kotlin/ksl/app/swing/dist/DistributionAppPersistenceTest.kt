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

package ksl.app.swing.dist

import ksl.app.dist.config.AnalysisDatasetEntry
import ksl.app.dist.config.AnalysisDocument
import ksl.app.dist.config.AnalysisDocumentToml
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.NamedFitConfiguration
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the GUI's reference-based persistence: opening re-materializes datasets
 * from their references; saving spills inline-pasted data to a sidecar CSV and
 * references it (never embedding values in the TOML); the document round-trips.
 */
class DistributionAppPersistenceTest {

    @Test
    fun `open re-materializes, save spills inline data to a sidecar, and the document round-trips`() {
        val controller = DistributionAppController("DistTest")
        try {
            val dir = Files.createTempDirectory("dist-persist")
            // A hand-built document: a Generated dataset (a real reference) and an
            // Inline (pasted) dataset — the latter is the convenience that must not
            // be embedded when the app saves.
            val doc = AnalysisDocument(
                analysisName = "demo",
                datasets = listOf(
                    AnalysisDatasetEntry(
                        NamedFitConfiguration(
                            "gen",
                            FitConfiguration(
                                dataSource = DataSourceReference.Generated(
                                    RVData(RVType.Exponential, mapOf("mean" to doubleArrayOf(5.0))),
                                    sampleSize = 50, streamNumber = 1, name = "gen"
                                )
                            )
                        )
                    ),
                    AnalysisDatasetEntry(
                        NamedFitConfiguration(
                            "pasted",
                            FitConfiguration(
                                dataSource = DataSourceReference.Inline(
                                    mapOf("pasted" to doubleArrayOf(1.0, 2.0, 3.0, 4.0))
                                )
                            )
                        )
                    )
                )
            )
            val inPath = dir.resolve("in.toml")
            Files.writeString(inPath, AnalysisDocumentToml.encode(doc))

            // Open: both datasets re-materialize (Generated samples; Inline embeds).
            val open1 = controller.openDocument(inPath)
            assertTrue(open1.failures.isEmpty(), "open should not fail: ${open1.failures}")
            assertEquals(listOf("gen", "pasted"), controller.collection.value.map { it.name })

            // Save: the pasted dataset is spilled to a sidecar; no values in the TOML.
            val outPath = dir.resolve("out.toml")
            controller.saveDocument(outPath)
            val text = Files.readString(outPath)
            assertTrue(text.contains("pasted.csv"), "saved TOML should reference the sidecar; got:\n$text")
            assertFalse(text.contains("4.0"), "saved TOML must not embed dataset values; got:\n$text")
            val sidecar = dir.resolve("data").resolve("pasted.csv")
            assertTrue(Files.exists(sidecar), "pasted data should be spilled to a sidecar CSV")

            // Re-open the saved document: same datasets, pasted values intact.
            val controller2 = DistributionAppController("DistTest")
            try {
                val open2 = controller2.openDocument(outPath)
                assertTrue(open2.failures.isEmpty(), "reopen should not fail: ${open2.failures}")
                assertEquals(listOf("gen", "pasted"), controller2.collection.value.map { it.name })
                assertContentEquals(
                    doubleArrayOf(1.0, 2.0, 3.0, 4.0),
                    controller2.collection.value.first { it.name == "pasted" }.data
                )
            } finally {
                controller2.dispose()
            }
        } finally {
            controller.dispose()
        }
    }
}
