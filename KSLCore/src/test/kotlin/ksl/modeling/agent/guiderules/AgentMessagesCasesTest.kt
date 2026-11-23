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

package ksl.modeling.agent.guiderules

import ksl.modeling.agent.AgentLike
import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.MovableAgentResource
import ksl.modeling.agent.Point2D
import ksl.modeling.agent.receiveMessageOfType
import ksl.modeling.agent.sendMessage
import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertSame

/**
 *  A rule of the agent guide's "Communication is messages delivered into mailboxes" (`ksl-agent.md`): a message's
 *  `from` is the sending agent, of any kind. A transient agent, a permanent agent and an agent resource are checked
 *  elsewhere; this class checks a movable agent resource replying under its own name.
 */
class AgentMessagesCasesTest {

    /** A customer asks a forklift, a movable agent resource whose statechart answers every request. */
    private class Floor(parent: ModelElement) : AgentModel(parent, "Floor") {
        val world: Context<AgentLike> = Context("world")
        val space: ContinuousProjection<AgentLike> = ContinuousProjection(world, 0.0..100.0, 0.0..100.0)
        val forklift = MovableAgentResource(this, space, initPosition = Point2D(0.0, 0.0), name = "Forklift")
        var replyFrom: AgentLike? = null

        init {
            forklift.statechart {
                initial("Open")
                state("Open") {
                    onMessage<AgentMessage.Request<String>> { question ->
                        question.from.mailbox.deliver(AgentMessage.Inform(forklift, "answer"))
                    }
                }
            }
        }

        inner class Customer : Agent("Customer") {
            val p: KSLProcess = process {
                delay(1.0)
                sendMessage(AgentMessage.Request(this@Customer, "question"), forklift.mailbox)
                replyFrom = receiveMessageOfType<AgentMessage.Inform<String>, AgentMessage>(this@Customer.mailbox).from
            }
        }

        override fun initialize() {
            super.initialize()
            replyFrom = null
            activate(Customer().p)
        }
    }

    @Test
    @DisplayName("a movable agent resource's reply names the movable agent resource as its sender")
    fun movableAgentResourceRepliesUnderItsOwnName() {
        val model = Model("MovableSender${System.nanoTime()}", autoCSVReports = false)
        val floor = Floor(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        assertSame(floor.forklift, floor.replyFrom)
    }
}
