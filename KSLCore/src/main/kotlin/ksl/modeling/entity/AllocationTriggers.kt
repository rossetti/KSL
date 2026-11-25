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

/**
 *  Fires when units of [allocation] go missing, to a failure or a preemption, with the cause as its value.
 *  It fires at once when units are missing already. Made by `Allocation.interruptedTrigger`.
 */
internal class InterruptedTrigger(val allocation: Allocation) : WaitTrigger<InterruptCause>() {

    override fun canFireNow(entity: ProcessModel.Entity): Boolean = allocation.isAllocated && allocation.hasMissingUnits

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine): InterruptCause = allocation.currentCause

    override fun validate(entity: ProcessModel.Entity) = allocation.requireHeldForTrigger(onRestore = false)

    override fun register(group: WaitGroup): Registration = allocation.registerTrigger(this, group, onRestore = false)

    override fun toString(): String = "InterruptedTrigger(allocation ${allocation.id} of ${allocation.myResource.name})"
}

/**
 *  Fires when the missing units of [allocation] have all come back. It fires at once when none are missing.
 *  Made by `Allocation.restoredTrigger`.
 */
internal class RestoredTrigger(val allocation: Allocation) : WaitTrigger<Unit>() {

    override fun canFireNow(entity: ProcessModel.Entity): Boolean = allocation.isAllocated && !allocation.hasMissingUnits

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine) {}

    override fun validate(entity: ProcessModel.Entity) = allocation.requireHeldForTrigger(onRestore = true)

    override fun register(group: WaitGroup): Registration = allocation.registerTrigger(this, group, onRestore = true)

    override fun toString(): String = "RestoredTrigger(allocation ${allocation.id} of ${allocation.myResource.name})"
}

/**
 *  A wait on one of an allocation's triggers. It waits in the allocation's list, which claims it when its
 *  units go missing ([onRestore] false) or all come back ([onRestore] true); a loser or a terminated wait
 *  leaves the list. A released allocation never claims it.
 */
internal class AllocationRegistration(
    trigger: WaitTrigger<*>,
    group: WaitGroup,
    val allocation: Allocation,
    val onRestore: Boolean
) : Registration(trigger, group) {

    override fun onReleaseAsLoser() {
        allocation.myTriggerRegistrations.remove(this)
    }

    override fun onReleaseOnTermination() {
        allocation.myTriggerRegistrations.remove(this)
    }

    override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
        // the allocation removed this registration from its list when it claimed the wait
        markReleased()
        return AfterResume.Value(group.value)
    }
}
