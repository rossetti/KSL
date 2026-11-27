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

package ksl.examples.general.processview.resources

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 *  Where a resource behavior keeps state that must start over in each replication. A doctor takes a 10-minute break
 *  every hour, and every third break is a long one, 30 minutes. Counting the breaks needs a variable that lasts from
 *  one break to the next. The three forms below differ only in where that variable lives.
 */
enum class BreakCount {
    /** Captured from outside a cyclic block: it carries over between replications. The trap. */
    CAPTURED,

    /** Captured from outside a cyclic block, and reset by the model at the start of each replication. */
    RESET_IN_INITIALIZE,

    /** Local to a behavior run once per replication, which loops itself. */
    LOCAL_TO_BEHAVIOR
}

/** The doctor's breaks, counted the [BreakCount] way. */
class DoctorBreaks(parent: ModelElement, val counting: BreakCount) : ProcessModel(parent, "DoctorBreaks") {
    val doctor = ResourceWithQ(this, "Doctor")
    val longBreaks = Counter(this, "LongBreaks")

    /** The long breaks of each replication, in order. */
    val longBreaksPerReplication = mutableListOf<Int>()

    // created once, with the model
    private var breaksTaken = 0

    init {
        if (counting == BreakCount.LOCAL_TO_BEHAVIOR) countLocally() else countByProperty()
    }

    private fun countByProperty() = doctor.cyclicBehavior {
        delay(60.0)
        doctor.goOffShift()
        breaksTaken++
        if (breaksTaken % 3 == 0) longBreaks.increment()
        delay(if (breaksTaken % 3 == 0) 30.0 else 10.0)
        doctor.goOnShift()
    }

    private fun countLocally() = doctor.behavior {
        var taken = 0
        while (true) {
            delay(60.0)
            doctor.goOffShift()
            taken++
            if (taken % 3 == 0) longBreaks.increment()
            delay(if (taken % 3 == 0) 30.0 else 10.0)
            doctor.goOnShift()
        }
    }

    override fun initialize() {
        if (counting == BreakCount.RESET_IN_INITIALIZE) breaksTaken = 0
    }

    override fun replicationEnded() {
        longBreaksPerReplication += longBreaks.value.toInt()
    }
}

/**
 *  Runs each form for three replications of 3 hours, two breaks each, and prints the long breaks in each. Every
 *  replication should take none; the captured count takes one in the second and third.
 */
fun main() {
    for (counting in BreakCount.entries) {
        val model = Model("BehaviorState$counting", autoCSVReports = false)
        val shop = DoctorBreaks(model, counting)
        model.numberOfReplications = 3
        model.lengthOfReplication = 180.0
        model.simulate()
        println("$counting: long breaks in each replication = ${shop.longBreaksPerReplication}")
    }
}
