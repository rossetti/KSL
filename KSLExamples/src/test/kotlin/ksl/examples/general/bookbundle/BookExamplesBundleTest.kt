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
import java.nio.file.Files
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Verification for the book-examples bundle: that every nominated catalog
 *  input/output resolves against the built model's actual control /
 *  RV-parameter / response surface, and that each model builds and runs.
 *  This is the in-suite equivalent of a `kslpkg validate` pass, catching
 *  catalog key/name drift at test time.
 *
 *  Grows one book chapter at a time; the model-id list below is extended
 *  as later chapters land.
 */
class BookExamplesBundleTest {

    private val chapter4ModelIds = listOf(
        BookBundleFixture.DRIVE_THROUGH_PHARMACY_RESOURCE,
        BookBundleFixture.DRIVE_THROUGH_PHARMACY_QUEUE,
        BookBundleFixture.TANDEM_QUEUE,
    )

    private val chapter5ModelIds = listOf(
        BookBundleFixture.PALLET_WORK_CENTER,
    )

    private val chapter6ModelIds = listOf(
        BookBundleFixture.STEM_FAIR_MIXER,
        BookBundleFixture.TIE_DYE_TSHIRTS,
    )

    private val chapter7ModelIds = listOf(
        BookBundleFixture.WALK_IN_HEALTH_CLINIC,
        BookBundleFixture.STEM_FAIR_MIXER_ENHANCED,
        BookBundleFixture.STEM_FAIR_MIXER_ENHANCED_SCHED,
        BookBundleFixture.RQ_INVENTORY_SYSTEM,
    )

    private val chapter8ModelIds = listOf(
        BookBundleFixture.TEST_AND_REPAIR_RESOURCE_CONSTRAINED,
        BookBundleFixture.TANDEM_QUEUE_CONSTRAINED_MOVEMENT,
        BookBundleFixture.TANDEM_QUEUE_UNCONSTRAINED_MOVEMENT,
        BookBundleFixture.TEST_AND_REPAIR_MOVABLE_RESOURCES,
        BookBundleFixture.TEST_AND_REPAIR_CONVEYOR,
    )

    private val capstoneModelIds = listOf(
        BookBundleFixture.TWO_ECHELON_INVENTORY,
    )

    /** Every model the bundle is expected to expose so far. */
    private val allModelIds =
        chapter4ModelIds + chapter5ModelIds + chapter6ModelIds +
            chapter7ModelIds + chapter8ModelIds + capstoneModelIds

    private fun withBookBundle(block: (LoadedBundle) -> Unit) {
        // Assemble the 16 book builders into a manifest bundle JAR and load it (the same
        // path an app uses), instead of relying on classpath ServiceLoader discovery.
        val dir = Files.createTempDirectory("book-bundle-test")
        try {
            val jar = BookBundleFixture.assemble(dir)
            BundleLoader.loadJar(jar).single().use { block(it) }
        } finally {
            dir.toFile().deleteRecursively()
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
                // Shrink the run so the smoke stays fast: two replications, and cap
                // any long finite horizon (the steady-state job shops run a simulated
                // year by default).  Terminating models keep their natural (infinite)
                // length and simply run to completion.
                model.numberOfReplications = 2
                if (model.lengthOfReplication.isFinite() && model.lengthOfReplication > 2000.0) {
                    model.lengthOfReplication = 2000.0
                    model.lengthOfReplicationWarmUp = 0.0
                }
                model.simulate()
                assertTrue(
                    model.responses.isNotEmpty() || model.counters.isNotEmpty(),
                    "Model '$id' produced no responses or counters"
                )
            }
        }
    }
}
