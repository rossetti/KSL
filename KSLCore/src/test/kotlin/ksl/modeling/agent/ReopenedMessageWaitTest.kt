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

package ksl.modeling.agent

import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A wait reopened after a seize trigger's provisional win breaks takes a message left in the mailbox, and is
 *  claimed by it before the mailbox's observers hear of the message.
 */
class ReopenedMessageWaitTest {

    private class Clinic(parent: ModelElement) : AgentModel(parent) {
        val bed = ResourceWithQ(this, "Bed")
        val owner = PermanentAgent("Owner")
        val consumed = AgentSignal("MessageConsumed")
        val events = mutableListOf<String>()

        init {
            owner.mailbox.attachObserver(object : MailboxObserver<AgentMessage> {
                override fun onMessageConsumed(message: AgentMessage, currentSize: Int) {
                    events.add("consumed@$time")
                    consumed.fire()
                }
            })
        }

        inner class Holder : Entity() {
            val script = process {
                val held = seize(bed)
                delay(1.0)
                release(held)
                bed.fail(InterruptRule.RESUME)
                owner.mailbox.deliver(AgentMessage.Inform(owner, "reply"))
            }
        }

        inner class Receiver : Entity() {
            val script = process {
                val bedTrigger = bed.seizeTrigger()
                val messageTrigger = owner.mailbox.trigger()
                val result = waitForFirst(bedTrigger, messageTrigger, consumed.trigger(), timeout = 2.0)
                val winner = when (result.trigger) {
                    messageTrigger -> "message"
                    bedTrigger -> "bed"
                    else -> "other"
                }
                events.add("won:$winner@$time")
            }
        }

        override fun initialize() {
            super.initialize()
            activate(Holder().script)
            activate(Receiver().script, 0.1)
        }
    }

    /**
     *  The receiver waits for the first of a bed, a message and a signal the mailbox observer fires when a message is
     *  consumed. At 1 the holder releases the bed (the bed trigger wins provisionally), fails it (the win breaks and
     *  the wait reopens) and delivers a message, left in the mailbox while the win was undecided. The reopened wait
     *  takes the message and is claimed by it; the observer then hears of it, and its signal finds the wait over.
     */
    @Test
    @DisplayName("a reopened wait takes a pending message, and an observer acting on its consumption finds the wait ended")
    fun reopenedWaitTakesMessageBeforeObserversAct() {
        val model = Model("ReopenedMessageWait", autoCSVReports = false)
        val clinic = Clinic(model)
        model.lengthOfReplication = 3.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(failure, "the run stopped: $failure")
        assertEquals(listOf("consumed@1.0", "won:message@1.0"), clinic.events)
    }
}
