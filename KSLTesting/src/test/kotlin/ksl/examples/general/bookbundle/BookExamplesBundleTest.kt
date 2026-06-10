/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.bookbundle

import ksl.app.bundle.BundleLoader
import ksl.app.bundle.LoadedBundle
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Verification for the chapter-4 slice of [BookExamplesBundle]:
 *  ServiceLoader discovery, that every nominated catalog input/output
 *  resolves against the built model's actual control / RV-parameter /
 *  response surface, and that each model builds and runs.  This is the
 *  in-suite equivalent of a `kslpkg validate` pass, catching catalog
 *  key/name drift at test time.
 *
 *  Grows one book chapter at a time; the model-id list below is extended
 *  as later chapters land.
 */
class BookExamplesBundleTest {

    private val chapter4ModelIds = listOf(
        BookExamplesBundle.DRIVE_THROUGH_PHARMACY_RESOURCE,
        BookExamplesBundle.DRIVE_THROUGH_PHARMACY_QUEUE,
        BookExamplesBundle.TANDEM_QUEUE,
    )

    private val chapter5ModelIds = listOf(
        BookExamplesBundle.PALLET_WORK_CENTER,
    )

    private val chapter6ModelIds = listOf(
        BookExamplesBundle.STEM_FAIR_MIXER,
        BookExamplesBundle.TIE_DYE_TSHIRTS,
    )

    private val chapter7ModelIds = listOf(
        BookExamplesBundle.WALK_IN_HEALTH_CLINIC,
        BookExamplesBundle.STEM_FAIR_MIXER_ENHANCED,
        BookExamplesBundle.STEM_FAIR_MIXER_ENHANCED_SCHED,
        BookExamplesBundle.RQ_INVENTORY_SYSTEM,
    )

    /** Every model the bundle is expected to expose so far. */
    private val allModelIds =
        chapter4ModelIds + chapter5ModelIds + chapter6ModelIds + chapter7ModelIds

    private fun withBookBundle(block: (LoadedBundle) -> Unit) {
        val bundles = BundleLoader.loadFromClasspath()
        try {
            val match = bundles.firstOrNull { it.bundle.bundleId == BookExamplesBundle.BUNDLE_ID }
            assertNotNull(
                match,
                "Expected BookExamplesBundle on the classpath; got " +
                    bundles.map { it.bundle.bundleId }
            )
            block(match)
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `bundle is discovered with all expected models`() {
        withBookBundle { match ->
            val ids = match.bundle.models.map { it.modelId }
            assertTrue(
                ids.containsAll(allModelIds),
                "Bundle must expose every expected model; got $ids"
            )
        }
    }

    @Test
    fun `every catalog input and output resolves against the model surface`() {
        withBookBundle { match ->
            for (id in allModelIds) {
                val descriptor = match.descriptorFor(id)
                val catalog = descriptor.catalog
                assertNotNull(catalog, "Model '$id' must carry a catalog")
                assertTrue(catalog.nominatedInputs.isNotEmpty(), "Model '$id' must nominate inputs")
                assertTrue(catalog.nominatedOutputs.isNotEmpty(), "Model '$id' must nominate outputs")
                for (input in catalog.nominatedInputs) {
                    assertTrue(
                        input.key in descriptor.inputNames,
                        "Catalog input '${input.key}' for '$id' does not resolve; " +
                            "inputNames=${descriptor.inputNames}"
                    )
                }
                for (output in catalog.nominatedOutputs) {
                    assertTrue(
                        output.name in descriptor.responseNames,
                        "Catalog output '${output.name}' for '$id' does not resolve; " +
                            "responseNames=${descriptor.responseNames}"
                    )
                }
            }
        }
    }

    @Test
    fun `every model builds and runs`() {
        withBookBundle { match ->
            for (id in allModelIds) {
                val bundled = match.bundle.models.first { it.modelId == id }
                val model = bundled.builder().build(null, null)
                // Shrink only the replication count; keep each builder's natural
                // run configuration (some models are terminating and set no length).
                model.numberOfReplications = 2
                model.simulate()
                assertTrue(
                    model.responses.isNotEmpty() || model.counters.isNotEmpty(),
                    "Model '$id' produced no responses or counters"
                )
            }
        }
    }
}
