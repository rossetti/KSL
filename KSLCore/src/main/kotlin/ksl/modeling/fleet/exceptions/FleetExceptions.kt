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
package ksl.modeling.fleet.exceptions

/**
 * An assignment policy named a vehicle that had not declared itself available.
 *
 * Availability is asserted by a vehicle, never inferred by the dispatcher (invariant `A6`), so a
 * policy that returns a vehicle the dispatcher did not offer it has reached outside what it was
 * given. That is a policy defect rather than a modelling condition, which is why it raises instead
 * of being skipped: a silently dropped proposal would show up much later as a fleet that never
 * quite keeps up.
 */
class FleetDispatchException(message: String) : RuntimeException(message)

/**
 * An assignment was revoked after its load was aboard, or used after it had completed.
 *
 * Invariant `A4`: once a vehicle has taken possession, it finishes the delivery. Re-tasking in
 * flight is legitimate and supported, but only up to that instant.
 */
class FleetAssignmentException(message: String) : RuntimeException(message)

/**
 * A task was completed twice, or a suspended entity was resumed twice.
 *
 * Invariant `A9`: an entity that posted a transport task is resumed exactly once. A second resume
 * would run the load's process from a continuation that has already been consumed, and the symptom
 * appears far from the cause, so it is worth catching where it happens.
 */
class FleetProtocolException(message: String) : RuntimeException(message)

/**
 * The subsystem's own account of itself does not add up.
 *
 * Raised by the closing audit, and never by ordinary operation: unlike the three exceptions above,
 * which name things a *model* can do wrong, this one names something the subsystem has done wrong.
 * A model that provokes it has found a defect, and the message says which record disagrees with
 * which rather than merely that something is amiss -- because an audit that reports only that it
 * failed costs more time than it saves.
 *
 * An `IllegalStateException` rather than a `RuntimeException`, matching the space layer's
 * `ZoneInvariantViolation`: the two say the same kind of thing about the two halves of one model.
 */
class FleetInvariantViolation(message: String) : IllegalStateException(message)

/**
 * A tour policy returned a stop order the vehicle cannot execute: it added or dropped a stop, put a
 * set-down before its pickup, or overfilled the vehicle.
 *
 * Raised at the moment of planning rather than at the stop that would have failed, because the
 * decision and the fault are the policy's and a diagnostic that arrived three stops later would
 * name the wrong thing.
 */
class FleetTourException(message: String) : RuntimeException(message)
