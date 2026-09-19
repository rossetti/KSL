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
package ksl.modeling.fleet.policies

import ksl.modeling.fleet.Dispatcher
import ksl.modeling.fleet.Interruption
import ksl.modeling.fleet.InterruptionPolicyIfc
import ksl.modeling.fleet.VehicleInterruptionListenerIfc
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.charge
import ksl.modeling.entity.tow
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Somebody free walks to the stopped vehicle, looks at it, and pushes it out of the aisle if it is
 * in anybody's way.
 *
 * The procedure a site actually runs, written as a process because that is what it is. Every step
 * is a wait or a branch, and the only one the framework contributes is [ksl.modeling.entity.tow].
 *
 * **The order matters and is the point.** The technician is seized *before* the walk, because a
 * person who is busy elsewhere is not walking anywhere yet -- and on a fleet with one technician
 * and several vehicles that wait is most of the answer. The assessment happens *after* the walk,
 * because nobody knows what is wrong until they are standing at it. And whether to move the vehicle
 * is decided **after** the walk, from [Interruption.isObstructingNow], which by then is a real
 * question: somebody has had to be free and walk over, and a queue has had that long to form behind
 * the vehicle. [Interruption.isOnAThroughRoute] is the standing fact underneath it -- a vehicle
 * broken down on a spur nobody uses can be repaired where it stands, and the same vehicle across a
 * main aisle cannot -- and this policy tows when either is true, so that a vehicle dead in an aisle
 * is moved even on the run where nobody has yet arrived behind it.
 *
 * **What it does about a flat battery is to tow it to a charger and nothing else.** Charge is a
 * function of the vehicle's odometers, so no amount of standing at it helps; reaching a charger is
 * the only thing that does. If the fleet has no charger this policy leaves the vehicle where it is,
 * which is what the default does too.
 *
 * @param technicians who does the work. A `ResourceWithQ`, so the wait for one is reported as a
 *   queue without this policy having to count anything.
 * @param reportingDelay how long before anybody knows. Zero is a fleet that radios in immediately.
 * @param walkingTime how long it takes to reach the vehicle. A single distribution rather than a
 *   function of where it stopped, which is the right first cut for a site where walking anywhere
 *   takes about as long as walking anywhere else; a model where it does not should subclass.
 * @param assessmentTime how long the look takes.
 * @param refuge where an obstructing vehicle is pushed to. Must be reachable from anywhere a
 *   vehicle can stop, or the tow raises.
 * @param towVelocity how fast a person moves a dead vehicle. Slower than it drives.
 */
open class VisitAndAssessPolicy @JvmOverloads constructor(
    private val technicians: ResourceWithQ,
    private val walkingTime: RVariableIfc,
    private val assessmentTime: RVariableIfc,
    private val refuge: String,
    private val towVelocity: Double,
    private val reportingDelay: RVariableIfc? = null
) : InterruptionPolicyIfc {

    init {
        require(towVelocity > 0.0) { "A tow velocity must be > 0.0, but was $towVelocity." }
    }

    override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
        val vehicle = interruption.vehicle
        reportingDelay?.let { delay(it, suspensionName = "${vehicle.name}:reportingFailure") }
        val technician = seize(technicians, suspensionName = "${vehicle.name}:awaitingTechnician")
        delay(walkingTime, suspensionName = "${vehicle.name}:technicianWalking")
        delay(assessmentTime, suspensionName = "${vehicle.name}:assessing")
        when (interruption) {
            is Interruption.Failed -> {
                // Two questions, and the disjunction is deliberate. `isOnAThroughRoute` is a fact
                // about where it stopped and is true from the first instant; `isObstructingNow` is
                // a fact about who is stuck and needs time to become true, which the walk and the
                // assessment have now provided. Either is reason enough to move it.
                if (interruption.isOnAThroughRoute || interruption.isObstructingNow) {
                    tow(vehicle, refuge, towVelocity)
                }
                delay(interruption.repairTime, suspensionName = "${vehicle.name}:repair")
            }

            is Interruption.OutOfCharge -> {
                val charger = vehicle.system.nearestCharger(vehicle.currentLocationName)
                if (charger != null) {
                    tow(vehicle, charger, towVelocity)
                    charge(vehicle)
                }
            }
        }
        release(technician)
    }

    override fun toString(): String = "VisitAndAssessPolicy(refuge=$refuge, towVelocity=$towVelocity)"
}

/**
 * Asks the dispatcher to look at the board again whenever a vehicle stops or comes back.
 *
 * **Not automatic, and worth understanding why.** The dispatcher is woken by the two things that
 * change a decision from inside the subsystem -- a task being posted and a vehicle declaring itself
 * available -- and a breakdown is neither. A vehicle that stops mid-approach keeps its assignment,
 * declares nothing, and posts nothing, so nothing wakes the dispatcher and the task it was carrying
 * out sits with a vehicle that will not reach it for as long as the repair lasts.
 *
 * For most fleets that costs nothing: the default dispatching rule would not take the task back
 * anyway, because it has no rule for doing so. It matters for a fleet running a **re-tasking**
 * policy, which could hand the work to a vehicle that is still moving -- and which cannot, because
 * it is never asked. Attaching this is how a model says it wants to be asked.
 *
 * It is opt-in rather than built in because waking the dispatcher is an event, and adding one to
 * every breakdown would change the event sequence of every existing model that has failures --
 * including the order of things that happen at the same instant -- for a benefit only some of them
 * can use.
 *
 * ```kotlin
 * agv.attachInterruptionListener(ReconsiderOnInterruption(agv.dispatcher))
 * ```
 */
class ReconsiderOnInterruption(private val dispatcher: Dispatcher) : VehicleInterruptionListenerIfc {

    override fun stopped(interruption: Interruption) = dispatcher.reconsider()

    override fun returnedToService(interruption: Interruption, outOfServiceFor: Double) =
        dispatcher.reconsider()

    override fun outOfService(interruption: Interruption) = dispatcher.reconsider()

    override fun toString(): String = "ReconsiderOnInterruption"
}
