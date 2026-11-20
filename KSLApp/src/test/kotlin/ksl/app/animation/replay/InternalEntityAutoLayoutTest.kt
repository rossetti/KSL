/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.animation.replay

import ksl.animation.AnimationCapture
import ksl.animation.TraceFileReader
import ksl.app.animation.io.AnimationSource
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceFailureBasis
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 *  An auto-layout built from a trace leaves out the library's own entities: the one that waits out a resource's
 *  failures delays without seizing anything, and must not become an object class or a storage.
 */
class InternalEntityAutoLayoutTest {

    private class Clinic(parent: ModelElement) : ProcessModel(parent, "Clinic") {
        val doctor = ResourceWithQ(this, "Doctor")

        init {
            doctor.failures(ConstantRV(7.0), ConstantRV(1.0), ResourceFailureBasis.CALENDAR_TIME)
        }

        inner class Patient : Entity() {
            val visit = process(isDefaultProcess = true) {
                val a = seize(doctor)
                delay(2.0)
                release(a)
            }
        }

        override fun initialize() {
            schedule(::arrive, 1.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun arrive(event: KSLEvent<Nothing>) {
            activate(Patient().visit)
            schedule(::arrive, 3.0)
        }
    }

    @Test
    fun `a trace auto-layout makes no object class or storage for the library's own entities`() {
        val model = Model("InternalLayout${System.nanoTime()}", autoCSVReports = false)
        Clinic(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 30.0
        val file = Files.createTempFile("internal-layout", ".atf")
        try {
            val capture = AnimationCapture.toFile(model, file)
            model.simulate()
            capture.close()
            val (header, events) = TraceFileReader.readAll(file)
            assertTrue(events.any { it is ksl.animation.AnimationEvent.EntityCreated && it.entityType == "ResourceFailures" },
                "the failures entity must be in the trace for this to test anything")
            val layout = model.buildAutoLayout(AnimationSource(null, header, events))
            val internal = setOf("ResourceFailures", "ResourceBehavior")
            val classes = layout.objectClasses.map { it.typeName }
            val storages = layout.storages.map { it.suspensionName }
            assertTrue(classes.none { it in internal }, "object classes: $classes")
            assertTrue(storages.none { it in internal }, "storages: $storages")
            assertTrue("Patient" in classes, "the model's own type is still laid out: $classes")
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
