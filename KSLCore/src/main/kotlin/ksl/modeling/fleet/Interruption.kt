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
package ksl.modeling.fleet

import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.Zone

/**
 * A vehicle has stopped and cannot carry on by itself.
 *
 * Sealed, because what a modeller may need to know differs by cause while everything they need to
 * *decide* is the same: where the vehicle is, what it is holding, what it was doing, and who is now
 * stuck behind it. The guide path draws no distinction at all -- a flat battery, a breakdown and an
 * operator stopping the line are one event to it -- so the distinction lives here, where the
 * response is decided.
 *
 * An interruption is handed to the vehicle's [InterruptionPolicyIfc], which runs as a process and
 * may do anything a process may do: wait for a technician, delay for travel and assessment, tow the
 * vehicle out of the way. **The policy has no return value.** When it returns, the framework asks
 * the same question the movement gate asks -- is this vehicle fit to carry on? -- and acts on the
 * answer. A repair that finished leaves a repaired vehicle, so it continues; a charge policy that
 * did nothing leaves a flat vehicle, so it does not. One predicate, and no way for a policy to
 * claim it fixed something it did not.
 *
 * @property vehicle the vehicle that stopped
 * @property at when it stopped
 * @property location where it stopped, as a network location name
 * @property heldZones the zones it is denying to everyone else while it stands there
 * @property wasDoing what it was doing at the moment it stopped
 * @property task the work in hand, or null if it had none
 * @property loadIsAboard whether it is carrying the load it was given
 */
sealed class Interruption(
    val vehicle: FleetVehicle,
    val at: Double,
    val location: String,
    val heldZones: List<Zone>,
    val wasDoing: TransporterState,
    val task: Dispatcher.Task?,
    val loadIsAboard: Boolean
) {

    /**
     * The vehicles standing still right now because this one is in their way.
     *
     * Asked of the vehicle rather than computed here, because being in the way is a fact about a
     * *substrate*: a guide path answers it from what this vehicle holds and who is waiting for it,
     * and a substrate where vehicles do not share space answers "nobody" -- which is the honest
     * reading of a free path rather than a gap, since a model that asserts vehicles do not get in
     * each other's way has asserted exactly this.
     *
     * Read it again after time passes -- it is a live query, not a snapshot taken when the vehicle
     * stopped.
     */
    val obstructed: List<FleetVehicle>
        get() = vehicle.vehiclesObstructed()

    /**
     * True when at least one vehicle is being held up by this one **at this instant**.
     *
     * The name says *now* because that is the whole of what it means, and the difference matters.
     * A vehicle becomes blocked only when it tries to enter the next zone and is refused, so at the
     * moment a vehicle stops, nobody has tried yet and the answer is no. Twenty minutes later three
     * carts are queued behind it and the answer is yes. Both answers are correct.
     *
     * **So a policy that asks this as its first line never tows.** Measured on the chapter's shop:
     * over sixteen breakdowns, asking at the instant the vehicle stopped found an obstruction *not
     * once*, on a layout where leaving it in the aisle cost the healthy cart 77% of its time
     * blocked. Nothing errors; the model simply behaves as though towing were not configured.
     *
     * A realistic policy never meets this, because it cannot decide anything until somebody has
     * been told, been free, and walked to the vehicle -- and by then the queue is real. **Ask this
     * one after time has passed.** To decide before any has, ask [isOnAThroughRoute], which is a
     * fact about the layout and is true the instant the vehicle stops.
     */
    val isObstructingNow: Boolean
        get() = obstructed.isNotEmpty()

    /**
     * True when the vehicle is standing somewhere traffic with business elsewhere has to pass.
     *
     * The judgement a person makes on arriving at a dead vehicle, and the one this class can answer
     * **immediately**: it is a fact about the layout rather than about who happens to be queued, so
     * it does not need time to pass to be meaningful. False when every zone the vehicle holds is on
     * a spur or at a dead end -- which is what a spur is for.
     *
     * The pair it forms with [isObstructingNow] is the useful one. This says *is this a bad place to
     * stop*; the other says *is anybody actually stuck*. A policy with no delays before its decision
     * wants this one. A policy that has already spent time getting somebody to the vehicle can use
     * either, and the other is then the sharper question.
     */
    val isOnAThroughRoute: Boolean
        get() = heldZones.any { it.isOnAThroughRoute }

    /**
     * The vehicle has broken down.
     *
     * @property failureNumber how many times this vehicle has failed in this replication, this one
     *   included
     * @property repairTime how long the repair itself takes, drawn from the vehicle's failure model
     *   when the failure was booked. Handed over rather than left on the failure model for a policy
     *   to find, so that a policy which surrounds it with travel and waiting uses the same draw the
     *   default would have used, and a policy which ignores it is making a visible choice.
     */
    class Failed internal constructor(
        vehicle: FleetVehicle,
        at: Double,
        location: String,
        heldZones: List<Zone>,
        wasDoing: TransporterState,
        task: Dispatcher.Task?,
        loadIsAboard: Boolean,
        val failureNumber: Int,
        val repairTime: Double
    ) : Interruption(vehicle, at, location, heldZones, wasDoing, task, loadIsAboard) {

        override fun toString(): String =
            "Failed(${vehicle.name} at $location, failure $failureNumber, repair=$repairTime)"
    }

    /**
     * The vehicle has run out of charge.
     *
     * Nothing a policy can do at the vehicle will change that: charge is a function of the
     * odometers, so the only way back into service is to reach a charger, which means towing. A
     * policy that does nothing leaves the vehicle flat and the framework takes it out of service --
     * which is the right answer and the honest one, because a vehicle that has run flat on a guide
     * path is an obstruction for the rest of the run.
     */
    class OutOfCharge internal constructor(
        vehicle: FleetVehicle,
        at: Double,
        location: String,
        heldZones: List<Zone>,
        wasDoing: TransporterState,
        task: Dispatcher.Task?,
        loadIsAboard: Boolean
    ) : Interruption(vehicle, at, location, heldZones, wasDoing, task, loadIsAboard) {

        override fun toString(): String = "OutOfCharge(${vehicle.name} at $location)"
    }
}

/**
 * What happens when a vehicle stops and cannot carry on by itself.
 *
 * **A process, not a duration**, and that is the whole of the design. The real procedure around a
 * breakdown is: somebody is told, somebody who is free walks to the vehicle, looks at it, decides,
 * perhaps pushes it out of the aisle, repairs it, and puts it back to work. Every step of that is a
 * wait or a branch, and a duration can express none of them.
 *
 * `suspend` is what makes them expressible, exactly as it is for [ksl.modeling.fleet.policies.AssignmentPolicyIfc]:
 * a policy that returns immediately is the simple case, and one that first waits for a scarce
 * technician is the general one. They differ only in whether responding takes simulated time.
 *
 * Declared as a member extension on the process builder for the same reason `assign` is:
 * `KSLProcessBuilder` is `@RestrictsSuspension`, so a plain `suspend fun` on this interface would
 * not compile at the call site. Being an extension also means an implementation receives the real
 * process builder and may `seize`, `delay`, `hold` and [tow] -- rather than being confined to
 * whatever a context object thought to offer.
 *
 * The policy runs inside **the vehicle's own agent**, so while it is running the vehicle is doing
 * nothing else, is not available to the dispatcher, and keeps its assignment and its load. A
 * failure interrupts the tour; it does not revoke it.
 *
 * ## What the framework does around it
 *
 * - Books the failure before the call and clears it after, so a policy cannot forget to end a
 *   repair and `FracTimeFailed` spans the whole procedure rather than the repair alone.
 * - Asks whether the vehicle is fit to continue when the policy returns. If it is, the vehicle
 *   re-routes from wherever it now stands and finishes its tour. If it is not, it goes out of
 *   service for the rest of the replication, holding its zones.
 * - Requires the same reproducibility as any other policy: randomness comes from a model-controlled
 *   stream, never a global generator.
 */
fun interface InterruptionPolicyIfc {

    /** @param interruption what happened, where, and what is stuck behind it */
    suspend fun KSLProcessBuilder.handle(interruption: Interruption)
}

/**
 * Repairs a broken vehicle where it stands, and leaves a flat one flat.
 *
 * The default, and it is the whole of what the subsystem did before repair became a procedure: a
 * failure costs the drawn repair time and nothing else -- no technician to wait for, no walk to the
 * vehicle, no decision about whether it is in anybody's way. Every one of those is a delay or a
 * branch a modeller adds by writing their own policy.
 *
 * Doing nothing about a flat battery is not an oversight. Charge is a function of the odometers and
 * no amount of standing at the vehicle changes it, so the only way back into service is to reach a
 * charger. A policy that wants that must tow; this one does not, and the vehicle stays where it
 * stopped for the rest of the replication.
 */
class RepairInPlacePolicy : InterruptionPolicyIfc {

    override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
        when (interruption) {
            is Interruption.Failed -> delay(
                interruption.repairTime,
                suspensionName = "${interruption.vehicle.name}:repair"
            )

            is Interruption.OutOfCharge -> Unit
        }
    }

    override fun toString(): String = "RepairInPlacePolicy"
}

/**
 * Told when a vehicle stops and when it comes back.
 *
 * **Separate from [InterruptionPolicyIfc], and the split is the same one the guide path already
 * makes between a movement gate and an arrival listener.** A policy *decides*, so there is one of
 * them: two would need a rule for what to do when they disagree. A listener *observes*, so there
 * may be any number: observers do not conflict, and the things that want to know about a breakdown
 * -- a maintenance log, an andon board, a dispatcher with a re-tasking rule, a modeller's own
 * bookkeeping -- have nothing to do with one another.
 *
 * Attached to the fleet rather than to a vehicle, because a breakdown is a fleet event: it changes
 * what the fleet can do. [Interruption.vehicle] says which one, so a listener interested in a single
 * vehicle filters in one line.
 *
 * Called **synchronously**, from inside the vehicle's own process at a point where no simulated
 * time passes. A listener may read anything and may call [Dispatcher.reconsider]; it must not
 * suspend, which the type enforces, and it should not move anything -- deciding what happens to the
 * vehicle is the policy's job, and a listener that moved it would be racing the policy that is
 * about to.
 *
 * Every method has a do-nothing default, so an implementation names only the moments it cares about.
 */
interface VehicleInterruptionListenerIfc {

    /**
     * The vehicle has stopped and its policy is about to run.
     *
     * The vehicle still holds its assignment and its load. If it had not yet collected the load, the
     * assignment is still revocable -- so this is the moment a dispatcher with a re-tasking rule
     * would want to look at the board again.
     */
    fun stopped(interruption: Interruption) {}

    /**
     * The policy put the vehicle right and it is going back to work.
     *
     * @param outOfServiceFor how long the whole procedure took, from stopping to now
     */
    fun returnedToService(interruption: Interruption, outOfServiceFor: Double) {}

    /**
     * The policy returned and the vehicle is still not fit to carry on, so it is out for the rest of
     * the replication -- standing where it stopped and holding its zones.
     *
     * The fleet is permanently one vehicle smaller from here, which is worth telling a dispatcher
     * that was counting on it.
     */
    fun outOfService(interruption: Interruption) {}
}
