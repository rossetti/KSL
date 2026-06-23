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
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Smoke tests for [SimoptTestModelsBundle]: confirms ServiceLoader
 *  registration, both bundled models load through the classpath
 *  discovery path, and each descriptor exposes the expected
 *  `@KSLControl`-annotated decision-variable surface that the SimOpt
 *  app's input picker depends on.
 *
 *  These tests do not run the simulations — they only inspect the
 *  introspection contract.  Per-model run behaviour is covered by the
 *  textbook tests in their respective modules.
 */
class SimoptTestModelsBundleTest {

    @Test
    fun `bundle is discovered on the classpath`() {
        val bundles = BundleLoader.loadFromClasspath()
        try {
            val match = bundles.firstOrNull {
                it.bundle.bundleId == "ksl.examples.simopt-test-models"
            }
            assertNotNull(match,
                "Expected SimoptTestModelsBundle to be discovered; got " +
                    bundles.map { it.bundle.bundleId })
            assertTrue(
                match.bundle.models.map { it.modelId }.containsAll(
                    listOf(
                        SimoptTestModelsBundle.LK_OPT_MODEL_ID,
                        SimoptTestModelsBundle.RQ_OPT_MODEL_ID
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
    fun `LK opt descriptor exposes the expected controls and responses`() {
        val bundles = BundleLoader.loadFromClasspath()
        try {
            val match = bundles.first { it.bundle.bundleId == "ksl.examples.simopt-test-models" }
            val descriptor = match.descriptorFor(SimoptTestModelsBundle.LK_OPT_MODEL_ID)
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
    fun `RQ opt descriptor exposes Inventory child-element controls and responses`() {
        val bundles = BundleLoader.loadFromClasspath()
        try {
            val match = bundles.first { it.bundle.bundleId == "ksl.examples.simopt-test-models" }
            val descriptor = match.descriptorFor(SimoptTestModelsBundle.RQ_OPT_MODEL_ID)
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
