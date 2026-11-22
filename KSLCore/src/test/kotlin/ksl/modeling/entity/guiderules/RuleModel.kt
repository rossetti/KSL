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

package ksl.modeling.entity.guiderules

import ksl.modeling.agent.AgentModel
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.simulation.KSLEvent
import ksl.simulation.Model

/**
 *  A small model for checking one rule at a time. Elements (resources, queues, signals) are made with this model
 *  as their parent before it runs; `plan` adds what happens in each replication, run again at every
 *  initialization. `log` collects what the actors write, cleared at the start of each replication.
 */
internal class RuleModel(name: String = "Rules") :
    AgentModel(Model("$name${System.nanoTime()}", autoCSVReports = false), name) {

    val log = mutableListOf<String>()

    private val myPlans = mutableListOf<RuleModel.() -> Unit>()

    /** Adds what happens in every replication. */
    fun plan(plan: RuleModel.() -> Unit) {
        myPlans.add(plan)
    }

    /** An entity of the given priority whose default process is [body]. */
    inner class Actor(tag: String, prio: Int, val body: suspend KSLProcessBuilder.(Actor) -> Unit) : Entity(tag) {
        init {
            priority = prio
        }

        val script: KSLProcess = process(processName = "script-$tag", isDefaultProcess = true) { body(this@Actor) }
    }

    /** An item for a blocking queue, known by its tag. */
    inner class Item(val tag: String) : QObject() {
        override fun toString(): String = tag
    }

    /** Makes an actor and activates its process now. */
    fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.(Actor) -> Unit): Actor =
        Actor(tag, priority, body).also { activate(it.script) }

    /** Runs [action] in an event at time [t]. */
    fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
        schedule({ _: KSLEvent<Nothing> -> action() }, t, priority = priority)
    }

    override fun initialize() {
        super.initialize()
        log.clear()
        for (p in myPlans) p()
    }

    /** Runs the model and returns it. */
    fun simulate(length: Double, reps: Int = 1): RuleModel {
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.simulate()
        return this
    }
}
