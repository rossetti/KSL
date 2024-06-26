/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.book.chapter6

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Signal
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 *  Example 6.4
 *  This example illustrates how to use the Signal class to hold entities until a signal is sent.
 *  Once the signal is received the entity continues its processing.
 */
fun main(){
    val m = Model()
    SignalExample(m)
    m.numberOfReplications = 1
    m.lengthOfReplication = 50.0
    m.simulate()
    m.print()
}

class SignalExample(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val signal = Signal(this, "SignalExample")

    private inner class SignaledEntity : Entity() {
        val waitForSignalProcess: KSLProcess = process {
            println("$time > before waiting for the signal: ${this@SignaledEntity.name}")
            waitFor(signal)
            println("$time > after getting the signal: ${this@SignaledEntity.name}")
            delay(5.0)
            println("$time > after the second delay for entity: ${this@SignaledEntity.name}")
            println("$time > exiting the process of entity: ${this@SignaledEntity.name}")
        }
    }

    override fun initialize() {
        for (i in 1..10){
            activate(SignaledEntity().waitForSignalProcess)
        }
        schedule(this::signalEvent, 3.0)
    }

    private fun signalEvent(event: KSLEvent<Nothing>){
        signal.signal(0..4)
        println("$time > signaling the entities in range 0..4")
    }
}

