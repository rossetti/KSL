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

package ksl.app.swing.experiment

import ksl.app.config.ModelReference
import ksl.examples.general.appsupport.LKInventoryBundle
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 *  Tests for the Phase E5 model-descriptor flow on
 *  [ExperimentAppController].  Verifies that
 *  [ExperimentAppController.currentModelDescriptor] populates and
 *  clears in lockstep with [ExperimentAppController.modelReference]
 *  and [ExperimentAppController.loadedBundles].
 *
 *  Uses the LK Inventory bundle, which is auto-loaded from the
 *  classpath by the controller's `init` block (the
 *  `KSLAppSwingExperiment` module depends on `KSLExamples` which
 *  carries the bundle's `ServiceLoader` registration).  Asserting
 *  the bundle is loaded acts as both fixture-validity check and
 *  classpath sanity check.
 */
class ExperimentAppControllerModelDescriptorTest {

    private var controller: ExperimentAppController? = null

    @BeforeTest
    fun setUp() {
        controller = ExperimentAppController("ModelDescriptorTest")
    }

    @AfterTest
    fun tearDown() {
        controller?.close()
        controller = null
    }

    @Test
    fun `LK bundle is auto-loaded on construction (fixture sanity check)`() {
        val c = controller!!
        val bundleIds = c.loadedBundles.value.map { it.bundle.bundleId }
        assertContains(bundleIds, "ksl.examples.lk-inventory",
            "LK Inventory bundle should be auto-loaded from the classpath; got $bundleIds")
    }

    @Test
    fun `setModelReference to a loaded bundle's model populates currentModelDescriptor`() {
        val c = controller!!
        assertNull(c.currentModelDescriptor.value,
            "No descriptor before any model is selected")
        c.setModelReference(
            ModelReference.ByBundleAndModelId(
                bundleId = "ksl.examples.lk-inventory",
                modelId = LKInventoryBundle.MODEL_ID
            )
        )
        val descriptor = c.currentModelDescriptor.value
        assertNotNull(descriptor,
            "Descriptor should be populated when the ref points at a loaded bundle")
        // The LK Inventory model exposes several controls; not
        // pinning specific names so this test isn't brittle to
        // model edits, just confirming non-empty.
        val controlCount = descriptor.controls.numericControls.size +
            descriptor.controls.stringControls.size +
            descriptor.controls.jsonControls.size
        assert(controlCount > 0) {
            "LK Inventory model should expose at least one control; got $controlCount"
        }
    }

    @Test
    fun `setModelReference to a non-bundle ref leaves currentModelDescriptor null`() {
        val c = controller!!
        c.setModelReference(ModelReference.Embedded("WhateverEmbedded"))
        assertNull(c.currentModelDescriptor.value,
            "Embedded refs have no bundle-side descriptor source")

        c.setModelReference(ModelReference.ByProviderId("WhateverProvider"))
        assertNull(c.currentModelDescriptor.value,
            "ByProviderId refs have no bundle-side descriptor source")
    }

    @Test
    fun `setModelReference to an unknown bundleId leaves currentModelDescriptor null`() {
        val c = controller!!
        c.setModelReference(
            ModelReference.ByBundleAndModelId(
                bundleId = "no.such.bundle",
                modelId = "AnyModel"
            )
        )
        assertNull(c.currentModelDescriptor.value)
    }

    @Test
    fun `setModelReference to a known bundle but unknown modelId leaves descriptor null`() {
        val c = controller!!
        c.setModelReference(
            ModelReference.ByBundleAndModelId(
                bundleId = "ksl.examples.lk-inventory",
                modelId = "NoSuchModelInLKBundle"
            )
        )
        assertNull(c.currentModelDescriptor.value,
            "Bundle exists but the modelId doesn't — descriptor lookup should fail closed")
    }

    @Test
    fun `clearing the model reference via resetConfiguration drops the descriptor`() {
        val c = controller!!
        c.setModelReference(
            ModelReference.ByBundleAndModelId(
                bundleId = "ksl.examples.lk-inventory",
                modelId = LKInventoryBundle.MODEL_ID
            )
        )
        assertNotNull(c.currentModelDescriptor.value)
        c.resetConfiguration()
        assertNull(c.currentModelDescriptor.value)
    }

    @Test
    fun `loadConfiguration with a bundle-resolved ref repopulates the descriptor`() {
        val c = controller!!
        // Start with a different state, then load a config that
        // sets the LK Inventory ref.
        c.setModelReference(ModelReference.Embedded("Other"))
        assertNull(c.currentModelDescriptor.value)

        val loaded = ksl.app.config.experiment.ExperimentConfiguration(
            modelReference = ModelReference.ByBundleAndModelId(
                bundleId = "ksl.examples.lk-inventory",
                modelId = LKInventoryBundle.MODEL_ID
            ),
            factors = listOf(
                ksl.app.config.experiment.FactorSpec(
                    name = "OrderQty",
                    levels = listOf(10.0, 30.0),
                    binding = ksl.app.config.experiment.ControlBinding.Control(
                        "Inventory.orderQuantity"
                    )
                )
            ),
            designSpec = ksl.app.config.experiment.DesignSpec.FullFactorial
        )
        c.loadConfiguration(loaded)
        assertNotNull(c.currentModelDescriptor.value,
            "loadConfiguration should trigger descriptor refresh")
        assertEquals(LKInventoryBundle.MODEL_ID,
            (c.modelReference.value as ModelReference.ByBundleAndModelId).modelId)
    }
}
