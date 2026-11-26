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

package ksl.modeling.entity

import ksl.animation.AnimationEvent

/**
 *  The trigger of a signal, made by `Signal.trigger`. It never fires at once: a signal is an
 *  event, so the wait registers by joining the signal's hold queue, and a later signal to the
 *  entity claims the wait.
 */
internal class SignalTrigger(
    val signal: Signal,
    val waitPriority: Int?,
    val waitStats: Boolean
) : WaitTrigger<Unit>() {

    override fun canFireNow(entity: ProcessModel.Entity): Boolean = false

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine, suspensionName: String?) {
        error("A signal trigger cannot fire without a wait; canFireNow is always false.")
    }

    override fun register(group: WaitGroup): Registration {
        val entity = group.entity
        val registration = SignalRegistration(this, group)
        signal.holdTimed(entity, waitPriority ?: entity.priority, registration)
        entity.emitAnimation { AnimationEvent.WaitingForSignal(signal.time, entity.id, signal.name) }
        return registration
    }

    override val aloneState: AloneState
        get() = AloneState.WAITING_FOR_SIGNAL

    override fun toString(): String = "SignalTrigger(${signal.name})"
}

/**
 *  A signal trigger's place in one wait: the entity in the signal's hold queue. A loser leaves
 *  the queue at the claim, recording its wait if the trigger records statistics; the winner leaves
 *  it after its resume, as an untimed wait for the signal does; termination records nothing.
 */
internal class SignalRegistration(
    private val signalTrigger: SignalTrigger,
    group: WaitGroup
) : Registration(signalTrigger, group) {

    override fun onReleaseAsLoser() {
        signalTrigger.signal.releaseTimed(group.entity, signalTrigger.waitStats)
    }

    override fun onReleaseOnTermination() {
        signalTrigger.signal.releaseTimed(group.entity, false)
    }

    override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
        markReleased()
        val signal = signalTrigger.signal
        val entity = group.entity
        signal.releaseTimed(entity, signalTrigger.waitStats)
        entity.emitAnimation { AnimationEvent.SignalReceived(signal.time, entity.id, signal.name) }
        return AfterResume.Value(Unit)
    }
}
