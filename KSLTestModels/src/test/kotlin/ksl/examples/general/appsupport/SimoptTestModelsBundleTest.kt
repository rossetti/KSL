/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.appsupport

import ksl.app.bundle.BundleLoader
import ksl.app.bundle.LoadedBundle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Smoke tests for the SimOpt fixture models: assembles them into a manifest bundle
 *  (from the named [LKInventoryOptModelBuilder] / [RQInventoryOptModelBuilder]), loads
 *  it, and confirms each descriptor exposes the expected `@KSLControl`-annotated
 *  decision-variable surface that the SimOpt app's input picker depends on.
 *
 *  These tests do not run the simulations — they only inspect the introspection
 *  contract. Per-model run behaviour is covered by the textbook tests.
 */
class SimoptTestModelsBundleTest {

    /** Assembles the two SimOpt models as one manifest bundle and loads it. */
    private fun loadBundle(dir: Path): List<LoadedBundle> {
        val jar = ManifestBundleFixtures.assembleManifestBundle(
            dir, "simopt", "ksl.examples.simopt-test-models",
            LKInventoryOptModelBuilder::class.java, RQInventoryOptModelBuilder::class.java,
        )
        return BundleLoader.loadJar(jar)
    }

    @Test
    fun `bundle assembles with both SimOpt models`(@TempDir dir: Path) {
        val bundles = loadBundle(dir)
        try {
            val match = bundles.firstOrNull {
                it.bundle.bundleId == "ksl.examples.simopt-test-models"
            }
            assertNotNull(match,
                "Expected the SimOpt bundle; got " + bundles.map { it.bundle.bundleId })
            assertTrue(
                match.bundle.models.map { it.modelId }.containsAll(
                    listOf(
                        "LKInventoryOpt",
                        "RQInventoryOpt"
                    )
                ),
                "Bundle must expose both LKInventoryOpt and RQInventoryOpt; got " +
                    match.bundle.models.map { it.modelId }
            )
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `LK opt descriptor exposes the expected controls and responses`(@TempDir dir: Path) {
        val bundles = loadBundle(dir)
        try {
            val match = bundles.first { it.bundle.bundleId == "ksl.examples.simopt-test-models" }
            val descriptor = match.descriptorFor("LKInventoryOpt")
            val controlKeys = descriptor.controls.numericControls.map { it.keyName }.toSet()
            val expected = listOf(
                "orderQuantity",
                "reorderPoint",
                "initialInventoryLevel",
                "holdingCost",
                "costPerItem",
                "backLogCost",
                "setupCost"
            )
            for (key in expected) {
                assertTrue(
                    controlKeys.any { it.endsWith(".$key") || it == key },
                    "Expected a control key ending in '.$key' (or named '$key' if " +
                        "the holding element is the Model root); got $controlKeys"
                )
            }
            assertTrue(descriptor.responseNames.isNotEmpty(),
                "LK model must expose at least one Response; got ${descriptor.responseNames}")
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `RQ opt descriptor exposes Inventory child-element controls and responses`(@TempDir dir: Path) {
        val bundles = loadBundle(dir)
        try {
            val match = bundles.first { it.bundle.bundleId == "ksl.examples.simopt-test-models" }
            val descriptor = match.descriptorFor("RQInventoryOpt")
            val controlKeys = descriptor.controls.numericControls.map { it.keyName }.toSet()
            // The RQ controls live on the RQInventory child element named "Inventory:Item"
            // (see RQInventorySystem.kt:62).  The Controls framework prefixes the child
            // element's keyName onto its property names.
            val expected = listOf(
                "initialOnHand",
                "initialReorderPoint",
                "initialReorderQty",
                "costPerOrder",
                "unitHoldingCost",
                "unitBackOrderCost"
            )
            for (key in expected) {
                assertTrue(
                    controlKeys.any { it.endsWith(".$key") },
                    "Expected a control key ending in '.$key' (RQ child-element-prefixed); " +
                        "got $controlKeys"
                )
            }
            assertTrue(descriptor.responseNames.isNotEmpty(),
                "RQ model must expose at least one Response; got ${descriptor.responseNames}")
        } finally {
            bundles.forEach { it.close() }
        }
    }
}
