/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.animationbundle

import ksl.animation.AnimationLayout
import ksl.animation.KSLAnimatedEntity
import ksl.animation.KSLAnimatedProcess
import ksl.animation.animation
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.entityType
import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Example 14 — the **discovery constructs** (10.1a/b): a clinic that declares its animatable entity types
 * with `entityType<T>()` and annotates its processes/entities with `@KSLAnimatedProcess`/`@KSLAnimatedEntity`.
 *
 * It exists to exercise the pre-run animation **inventory**: `Patient` and `VipPatient` are declared (so they
 * appear in `inventory.entityTypes` and the Object-Styles type dropdown before any run), each with an
 * annotated process; the internal `Janitor` is `@KSLAnimatedEntity(include = false)`, so it is discovered but
 * flagged out of capture/styling — the same pattern the library uses for a movable resource's home-base driver.
 */
object Example14AnnotatedClinic {

    fun buildModel(): Model {
        val m = Model("AnnotatedClinicModel")
        AnnotatedClinic(m, "Clinic")
        m.numberOfReplications = 1
        m.lengthOfReplication = 240.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Annotated Clinic (declared entity types + annotated processes)"
        size(640.0, 380.0)
        clock(24.0, 30.0)
        // The declared types drive the glyph styles (matched by the names entityType<T>() surfaces).
        objectClass("Patient") { color = "#1f77b4"; size = 14.0 }
        objectClass("VipPatient") { color = "#d62728"; size = 14.0 }
        line(320.0 to 170.0, 440.0 to 170.0, color = "#999999")
        queue("Nurse:Q", 320.0, 170.0) { growthDegrees = 90.0 }
        resource("Nurse", 470.0, 170.0)
    }

}

/**
 * A minimal clinic process model that exercises the discovery constructs:
 *  - `entityType<Patient>()` / `entityType<VipPatient>()` — declared, so they surface pre-run;
 *  - `@KSLAnimatedProcess` — names the process as it appears in the trace;
 *  - `@KSLAnimatedEntity(include = false)` on the internal `Janitor` — discovered but opted out.
 */
class AnnotatedClinic(parent: ksl.simulation.ModelElement, name: String? = "Clinic") : ProcessModel(parent, name) {

    private val nurse = ResourceWithQ(this, "Nurse", capacity = 1)
    private val serviceTime = RandomVariable(this, ExponentialRV(6.0))
    private val tbaPatient = RandomVariable(this, ExponentialRV(8.0))
    private val tbaVip = RandomVariable(this, ExponentialRV(30.0))

    // Declared animatable entity types (10.1a): surfaced to the inventory before any run.
    val patientType = entityType<Patient>()
    val vipType = entityType<VipPatient>()

    override fun initialize() {
        schedule(this::patientArrival, tbaPatient)
        schedule(this::vipArrival, tbaVip)
        schedule(this::cleaning, 30.0)
    }

    private fun patientArrival(e: KSLEvent<Nothing>) { activate(Patient().visit); schedule(this::patientArrival, tbaPatient) }
    private fun vipArrival(e: KSLEvent<Nothing>) { activate(VipPatient().vipVisit); schedule(this::vipArrival, tbaVip) }
    private fun cleaning(e: KSLEvent<Nothing>) { activate(Janitor().clean); schedule(this::cleaning, 60.0) }

    inner class Patient : Entity() {
        @KSLAnimatedProcess(name = "visit")
        val visit = process(processName = "visit") {
            val a = seize(nurse); delay(serviceTime); release(a)
        }
    }

    inner class VipPatient : Entity() {
        @KSLAnimatedProcess(name = "vipVisit")
        val vipVisit = process(processName = "vipVisit") {
            val a = seize(nurse); delay(serviceTime); release(a)
        }
    }

    /** Internal upkeep — discovered by reflection but opted out of the animation inventory. */
    @KSLAnimatedEntity(include = false)
    inner class Janitor : Entity() {
        @KSLAnimatedProcess(include = false)
        val clean = process(processName = "clean") { delay(2.0) }
    }
}

