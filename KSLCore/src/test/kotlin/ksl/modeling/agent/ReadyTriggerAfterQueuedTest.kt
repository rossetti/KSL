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

import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.RequestSelectionRuleIfc
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A ready trigger that wins a wait after a queued one has registered claims the wait before it fires, so that
 *  nothing its firing sets off can claim the wait for the queued one.
 */
class ReadyTriggerAfterQueuedTest {

    private class Ward(parent: ModelElement) : AgentModel(parent) {
        // a queue with a selection rule, so that a seize trigger on it is decided only once its request is queued
        val bedQ = RequestQ(this, "BedQ").also { q ->
            q.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                var left = resource.numAvailableUnits
                requestQ.filter { r -> (r.amountRequested <= left).also { if (it) left -= r.amountRequested } }
            }
        }
        val bed = ResourceWithQ(this, "Bed", capacity = 1, queue = bedQ)
        val owner = PermanentAgent("Owner")
        val events = mutableListOf<String>()

        init {
            // taking the message opens a bed: the queued bed request could be served by it
            owner.mailbox.attachObserver(object : MailboxObserver<AgentMessage> {
                override fun onMessageConsumed(message: AgentMessage, currentSize: Int) {
                    bed.changeCapacity(bed.CapacityChangeNotice(capacity = 2))
                }
            })
        }

        inner class Holder : Entity() {
            val script = process {
                seize(bed)
                delay(20.0)
            }
        }

        inner class Receiver : Entity() {
            val script = process {
                val bedTrigger = bed.seizeTrigger()
                val messageTrigger = owner.mailbox.trigger()
                val result = waitForFirst(bedTrigger, messageTrigger, timeout = 5.0)
                events.add("won:${if (result.trigger === messageTrigger) "message" else "other"}@$time")
            }
        }

        inner class Later : Entity() {
            val script = process {
                val a = seize(bed)
                events.add("later got the bed@$time")
                release(a)
            }
        }

        override fun initialize() {
            super.initialize()
            owner.mailbox.deliver(AgentMessage.Inform(owner, "ready"))
            activate(Holder().script)
            activate(Receiver().script, 0.5)
            activate(Later().script, 1.0)
        }
    }

    /**
     *  The one bed is held from 0. At 0.5 the receiver waits for the first of a bed (its queue has a rule, so the bed
     *  trigger registers first) and a message already in the mailbox, which wins at once. Taking the message opens a
     *  second bed. The wait is over, so that bed stays free, and a later seize at 1 takes it then.
     */
    @Test
    @DisplayName("a ready trigger's firing cannot hand the wait to the queued trigger it beat")
    fun readyTriggerClaimsBeforeItFires() {
        val model = Model("ReadyTriggerAfterQueued", autoCSVReports = false)
        val ward = Ward(model)
        model.lengthOfReplication = 10.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(failure, "the run stopped: $failure")
        assertEquals(listOf("won:message@0.5", "later got the bed@1.0"), ward.events)
    }
}
