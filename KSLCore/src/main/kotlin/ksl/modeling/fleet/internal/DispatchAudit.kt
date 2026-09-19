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
package ksl.modeling.fleet.internal

import ksl.modeling.fleet.FleetSystem
import ksl.modeling.fleet.AssignmentState
import ksl.modeling.fleet.Dispatcher
import ksl.modeling.fleet.TaskState
import ksl.modeling.fleet.exceptions.FleetInvariantViolation

/**
 * Audits the dispatcher's account of the world against the vehicles' account of it, once, as a
 * replication ends.
 *
 * The space layer has its own checker and it asserts things about *space* -- who is standing where,
 * and who is denying which zone to whom. That is not where this subsystem's own mistakes live. Its
 * mistakes live in the account two independent parties keep of the same commitment: a dispatcher
 * that believes a task is assigned and a vehicle that has forgotten it, a task terminated under a
 * vehicle already on its way to collect it, a load suspended in a hold queue with nothing left in
 * the model that will ever wake it. None of those has an immediate symptom. Each of them surfaces
 * much later as an illegal state transition, a hang, or -- worst -- as a set of perfectly plausible
 * averages over the work that happened to survive.
 *
 * Every check here is about **consistency, not tidiness**. A replication may legitimately end with
 * loads waiting, a vehicle part-way through a tour, and a queue full of work; that is what a busy
 * system looks like when the clock stops, and the horizon diagnostics already report it. What may
 * not happen is that the parties disagree about it.
 */
internal class DispatchAudit(
    private val system: FleetSystem
) {

    private val dispatcher: Dispatcher
        get() = system.dispatcher

    /** Runs every check, throwing on the first violation found. */
    fun checkClosing() {
        checkAssignmentsAgreeWithTasks()
        checkNoTaskIsHeldTwice()
        checkQueuedTasksAgreeWithVehicles()
        checkSuspendedLoadsHaveLiveTasks()
        checkSuspendedRidersHaveSomewhereToBe()
        checkTaskConservation()
        checkAssignmentConservation()
    }

    /**
     * A vehicle's open assignment names a task that is still live, and the two agree on its stage.
     *
     * The failure this catches has a name in this subsystem's history: a task cancelled or
     * terminated while a vehicle was on its way to collect it left the vehicle holding an assignment
     * on a dead task, and the control loop then raised "Task cannot go from CANCELLED to
     * IN_PROGRESS" at the moment of collection -- with nothing in the message pointing back to the
     * cancellation that caused it.
     */
    private fun checkAssignmentsAgreeWithTasks() {
        for (vehicle in system.vehicles) {
            for (assignment in vehicle.assignments) {
                val task = assignment.task
            if (task.isTerminal) {
                violate(
                    "vehicle (${vehicle.name}) still holds an assignment on task (${task.name}), " +
                            "which is ${task.state}: whatever ended that task did not release the " +
                            "vehicle committed to it"
                )
            }
            if (assignment.state == AssignmentState.REVOKED) {
                violate(
                    "vehicle (${vehicle.name}) still holds an assignment on task (${task.name}) " +
                            "that has been revoked, so it is on its way to collect work it no " +
                            "longer has"
                )
            }
            val expected = when (assignment.state) {
                AssignmentState.ASSIGNED -> TaskState.ASSIGNED
                AssignmentState.IN_PROGRESS -> TaskState.IN_PROGRESS
                AssignmentState.COMPLETED -> TaskState.COMPLETED
                AssignmentState.REVOKED -> null
            }
            if (expected != null && task.state != expected) {
                violate(
                    "the assignment of task (${task.name}) to vehicle (${vehicle.name}) is " +
                            "${assignment.state} while the task is ${task.state}: the vehicle and " +
                            "the dispatcher disagree about how far along the same commitment is"
                )
            }
                if (task.dispatcher !== dispatcher) {
                    violate(
                        "vehicle (${vehicle.name}) holds an assignment on task (${task.name}), " +
                                "which belongs to a different dispatcher"
                    )
                }
            }
        }
    }

    /** No two vehicles are on their way to the same task. */
    private fun checkNoTaskIsHeldTwice() {
        val holders = HashMap<Dispatcher.Task, String>()
        for (vehicle in system.vehicles) {
            for (task in vehicle.assignments.map { it.task }) {
                val already = holders.put(task, vehicle.name)
                if (already != null) {
                    violate(
                        "task (${task.name}) is held by both ($already) and (${vehicle.name}): " +
                                "two vehicles are on their way to collect one load"
                    )
                }
            }
        }
    }

    /**
     * The queue's account of which tasks have a vehicle matches the fleet's.
     *
     * A task waiting in the queue is `POSTED` until a vehicle commits and `ASSIGNED` afterwards, and
     * `assignedAt` is the instant of that commitment. Each of those is written by the dispatcher; the
     * assignment itself is held by the vehicle. So this compares two records of one fact, which is
     * the only kind of comparison worth making.
     */
    private fun checkQueuedTasksAgreeWithVehicles() {
        val assigned = system.vehicles.flatMap { v -> v.assignments.map { it.task } }.toSet()
        for (task in dispatcher.board.tasks) {
            val hasVehicle = task in assigned
            when (task.state) {
                TaskState.POSTED -> if (hasVehicle) {
                    violate(
                        "task (${task.name}) is waiting unassigned in the queue, but a vehicle " +
                                "holds an assignment on it"
                    )
                }

                TaskState.ASSIGNED -> if (!hasVehicle) {
                    violate(
                        "task (${task.name}) is recorded as assigned, but no vehicle holds an " +
                                "assignment on it: nothing in the model is coming for it"
                    )
                }

                else -> violate(
                    "task (${task.name}) is ${task.state} but is still in the dispatcher's queue, " +
                            "where only posted and assigned work belongs"
                )
            }
            if (task.state == TaskState.ASSIGNED && task.assignedAt.isNaN()) {
                violate("task (${task.name}) is assigned but records no instant of commitment")
            }
            if (task.state == TaskState.POSTED && !task.assignedAt.isNaN()) {
                violate(
                    "task (${task.name}) is waiting unassigned but records a commitment at " +
                            "${task.assignedAt}, so a revocation left the instant behind"
                )
            }
        }
    }

    /**
     * Every load suspended in this subsystem is suspended on work that still exists.
     *
     * A load in a hold queue is resumed by exactly one thing -- the vehicle serving its task -- so a
     * load whose task has gone is not slow, it is lost. It waits out the replication and is counted
     * by the horizon diagnostic as though the run had simply been too short, which is the most
     * misleading thing this subsystem could report.
     */
    private fun checkSuspendedLoadsHaveLiveTasks() {
        val live = liveTasks()
        val awaitingCollection = live.filter {
            it.state == TaskState.POSTED || it.state == TaskState.ASSIGNED
        }.mapNotNull { it.waitingEntity }.toSet()
        val aboard = live.filter { it.state == TaskState.IN_PROGRESS }
            .mapNotNull { it.waitingEntity }.toSet()
        for (entity in system.loadsAwaitingPickup) {
            if (entity !in awaitingCollection) {
                violate(
                    "load (${entity.name}) is suspended waiting to be collected, but no task " +
                            "waiting for a vehicle names it: nothing will ever wake it"
                )
            }
        }
        // Riders are checked by checkSuspendedRidersHaveSomewhereToBe, which knows about the
        // manifest; this queue holds both kinds and a task-only reading of it would report every
        // rider as lost.
        val riders = system.vehicles.flatMap { v -> v.ridersAboard }.map { it.rider }.toSet()
        for (entity in system.loadsInTransit) {
            if (entity !in aboard && entity !in riders) {
                violate(
                    "load (${entity.name}) is suspended aboard a vehicle, but no task in progress " +
                            "names it: nothing will ever set it down"
                )
            }
        }
    }

    /**
     * Every rider suspended in this subsystem is suspended on something that still exists.
     *
     * The same statement as [checkSuspendedLoadsHaveLiveTasks] makes about posted work, and it has
     * to be made separately because a rider has no task: it is woken by a stop's line or by a
     * vehicle's manifest, and neither of those is anything the task checks look at. The failure it
     * catches is the one that would otherwise be invisible -- a rider taken out of a stop's line by
     * a boarding that then did not put it on the manifest is not slow, it is lost, and the horizon
     * diagnostic would report it as though the run had merely been too short.
     */
    private fun checkSuspendedRidersHaveSomewhereToBe() {
        val waiting = system.stops.flatMap { it.waiting }.map { it.rider }.toSet()
        for (entity in system.loadsAwaitingBoarding) {
            if (entity !in waiting) {
                violate(
                    "load (${entity.name}) is suspended waiting to board, but no stop's line " +
                            "names it: nothing will ever take it"
                )
            }
        }
        val aboard = system.vehicles.flatMap { it.ridersAboard }.map { it.rider }.toSet()
        val carried = system.vehicles.flatMap { v -> v.assignments.map { it.task } }
            .filter { it.state == TaskState.IN_PROGRESS }
            .mapNotNull { it.waitingEntity }.toSet()
        for (entity in system.loadsInTransit) {
            if (entity !in aboard && entity !in carried) {
                violate(
                    "load (${entity.name}) is suspended aboard a vehicle, but neither a task in " +
                            "progress nor any vehicle's rider list names it: nothing will ever " +
                            "set it down"
                )
            }
        }
        // A rider on a manifest is a rider on exactly one manifest.
        val holders = HashMap<Any, String>()
        for (vehicle in system.vehicles) {
            for (ride in vehicle.ridersAboard) {
                val already = holders.put(ride, vehicle.name)
                if (already != null) {
                    violate(
                        "ride (${ride.name}) is aboard both ($already) and (${vehicle.name})"
                    )
                }
            }
        }
    }

    /**
     * Every task posted is accounted for: completed, cancelled, still queued, or under way.
     *
     * **Skipped when the model has a warm-up.** The counters are reset at the warm-up point and the
     * queue is not, deliberately and correctly -- the counters measure the post-warm-up run while
     * the queue holds work that has been waiting since before it. Their difference is then a fact
     * about the warm-up rather than a discrepancy, and asserting over it would report a defect on
     * every steady-state model in the library. Shadowing the counters to get around that would put a
     * second source of truth beside a published statistic, which this subsystem avoids everywhere
     * else and will not start doing for the benefit of its own audit.
     */
    private fun checkTaskConservation() {
        if (system.model.lengthOfReplicationWarmUp > 0.0) return
        val posted = dispatcher.numTasksPosted.value
        val completed = dispatcher.numTasksCompleted.value
        val cancelled = dispatcher.numTasksCancelled.value
        val queued = dispatcher.board.tasks.size
        // Counted per ASSIGNMENT, not per vehicle. A vehicle carrying three loads is three tasks
        // under way, and counting it once makes two of them look lost -- the same defect family as
        // every other place a scalar became a collection, and the last place it was still hiding.
        val underway = system.vehicles.sumOf { v ->
            v.assignments.count { it.state == AssignmentState.IN_PROGRESS }
        }
        val accountedFor = completed + cancelled + queued + underway
        if (posted != accountedFor) {
            violate(
                "$posted task(s) were posted but ${accountedFor.toLong()} are accounted for " +
                        "(${completed.toLong()} completed, ${cancelled.toLong()} cancelled, " +
                        "$queued still queued, $underway under way): " +
                        "${(posted - accountedFor).toLong()} went missing"
            )
        }
    }

    /**
     * Every assignment made is accounted for: completed, revoked, or still open.
     *
     * Skipped under a warm-up, for the reason given on [checkTaskConservation]. Completed
     * assignments are counted by the completed *tasks*, because an assignment completes exactly when
     * the task it serves does and counting the same event twice would be the second source of truth
     * this subsystem keeps refusing to create.
     */
    private fun checkAssignmentConservation() {
        if (system.model.lengthOfReplicationWarmUp > 0.0) return
        val made = dispatcher.numAssignmentsMade.value
        val revoked = dispatcher.numAssignmentsRevoked.value
        val completed = dispatcher.numTasksCompleted.value
        val open = system.vehicles.sumOf { it.assignments.size }
        val accountedFor = revoked + completed + open
        if (made != accountedFor) {
            violate(
                "${made.toLong()} assignment(s) were made but ${accountedFor.toLong()} are " +
                        "accounted for (${revoked.toLong()} revoked, ${completed.toLong()} " +
                        "completed, $open still open): ${(made - accountedFor).toLong()} went missing"
            )
        }
    }

    /** Every task the model still knows about: those in the queue, and those being carried out. */
    private fun liveTasks(): List<Dispatcher.Task> {
        val queued = dispatcher.board.tasks
        val underway = system.vehicles.flatMap { v -> v.assignments.map { it.task } }
        return (queued + underway).distinct()
    }

    private fun violate(what: String): Nothing = throw FleetInvariantViolation(
        "AGV invariant violated in system (${system.name}) at time ${system.time}, as replication " +
                "${system.model.currentReplicationNumber} ended: $what."
    )
}
