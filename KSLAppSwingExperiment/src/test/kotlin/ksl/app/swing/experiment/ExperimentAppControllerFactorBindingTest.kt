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
import ksl.app.config.experiment.ControlBinding
import ksl.app.config.experiment.FactorSpec
import ksl.examples.general.appsupport.LKInventoryBundle
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 *  Pins the controller's behaviour around factor bindings — the
 *  controller does NOT validate the binding key against the model
 *  descriptor, so the Factors panel (and the engine glue at submit
 *  time) is where unresolvable bindings get caught.  These tests
 *  pin that contract so it doesn't drift.
 */
class ExperimentAppControllerFactorBindingTest {

    private var controller: ExperimentAppController? = null

    @BeforeTest
    fun setUp() {
        controller = ExperimentAppController("FactorBindingTest")
        controller!!.setModelReference(
            ModelReference.ByBundleAndModelId(
                bundleId = "ksl.examples.lk-inventory",
                modelId = LKInventoryBundle.MODEL_ID
            )
        )
    }

    @AfterTest
    fun tearDown() {
        controller?.close()
        controller = null
    }

    @Test
    fun `addFactor accepts a binding to a known control key`() {
        val c = controller!!
        val descriptor = c.currentModelDescriptor.value
        assertNotNull(descriptor, "Descriptor should be loaded for the LK Inventory model")
        val knownKey = descriptor.controls.numericControls.first().keyName
        c.addFactor(
            FactorSpec(
                name = "F1",
                levels = listOf(10.0, 30.0),
                binding = ControlBinding.Control(knownKey)
            )
        )
        assertEquals(1, c.factors.value.size)
        assertEquals(knownKey, (c.factors.value[0].binding as ControlBinding.Control).controlKey)
    }

    @Test
    fun `addFactor accepts a binding to a known RV parameter`() {
        val c = controller!!
        val descriptor = c.currentModelDescriptor.value
        assertNotNull(descriptor)
        // LK Inventory exposes at least one RV; pick one + its first
        // tunable parameter.  Skip if the model happens not to expose
        // any (the assertion below makes the dependency visible).
        val rvEntry = descriptor.rvParameterMap.entries.firstOrNull { it.value.isNotEmpty() }
        assertNotNull(rvEntry, "LK Inventory model is expected to expose at least one " +
            "tunable RV parameter for this test to mean anything")
        val paramName = rvEntry.value.keys.first()
        c.addFactor(
            FactorSpec(
                name = "RvFactor",
                levels = listOf(0.5, 1.0),
                binding = ControlBinding.RVParameter(
                    rvName = rvEntry.key,
                    paramName = paramName
                )
            )
        )
        assertEquals(1, c.factors.value.size)
        val b = c.factors.value[0].binding as ControlBinding.RVParameter
        assertEquals(rvEntry.key, b.rvName)
        assertEquals(paramName, b.paramName)
    }

    @Test
    fun `addFactor with an unknown control key still succeeds at controller level`() {
        // Pin this behaviour: the controller is purely a state-holder
        // and does NOT validate the binding key against the model.
        // That's the panel's job (live feedback) + the engine glue's
        // (submit-time enforcement via ParallelDesignedExperiment's
        // validateInputKeys precondition).  If this test starts
        // failing, the controller has grown a descriptor-coupled
        // validator — re-evaluate whether the Factors panel still
        // needs to do the same check.
        val c = controller!!
        c.addFactor(
            FactorSpec(
                name = "UnresolvedFactor",
                levels = listOf(0.0, 1.0),
                binding = ControlBinding.Control("not.a.real.control.key")
            )
        )
        assertEquals(1, c.factors.value.size)
    }
}
