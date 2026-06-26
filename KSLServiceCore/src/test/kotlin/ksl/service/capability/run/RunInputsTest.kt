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

package ksl.service.capability.run

import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proves [RunInputs.bind] routes an agent's input map (the keys `describe_model`
 * advertises) to the correct override form — numeric control vs RV parameter —
 * and rejects unknown keys, all against the real MM1 descriptor.
 */
class RunInputsTest {

    @Test
    fun `routes a control key to a control override and an rv key to an rv override`() {
        BundleRegistry.fromClasspath().use { registry ->
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val controlKey = descriptor.controls.numericControls.first().keyName
            val rv = descriptor.rvParameterData.first()
            val rvKey = "${rv.rvName}${RVParameterSetter.rvParamConCatChar}${rv.paramName}"

            val bound = RunInputs.bind(descriptor, mapOf(controlKey to 4.0, rvKey to 2.5))

            // The control routed to controlOverrides with the new value.
            assertEquals(1, bound.controlOverrides.numericControls.size)
            val control = bound.controlOverrides.numericControls.single()
            assertEquals(controlKey, control.keyName)
            assertEquals(4.0, control.value)

            // The RV parameter routed to rvOverrides.
            assertEquals(1, bound.rvOverrides.size)
            assertEquals(rv.rvName, bound.rvOverrides.single().rvName)
            assertEquals(rv.paramName, bound.rvOverrides.single().paramName)
            assertEquals(2.5, bound.rvOverrides.single().value)
        }
    }

    @Test
    fun `an unknown input key is rejected with a helpful message`() {
        BundleRegistry.fromClasspath().use { registry ->
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val error = assertFailsWith<IllegalArgumentException> {
                RunInputs.bind(descriptor, mapOf("not.a.real.input" to 1.0))
            }
            assertTrue("not.a.real.input" in (error.message ?: ""))
        }
    }

    @Test
    fun `an empty input map binds to empty overrides`() {
        BundleRegistry.fromClasspath().use { registry ->
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val bound = RunInputs.bind(descriptor, emptyMap())
            assertTrue(bound.controlOverrides.numericControls.isEmpty())
            assertTrue(bound.rvOverrides.isEmpty())
        }
    }
}
