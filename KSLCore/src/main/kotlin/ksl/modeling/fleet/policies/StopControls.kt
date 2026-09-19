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

import ksl.modeling.fleet.FleetVehicle
import ksl.modeling.fleet.Stop
import ksl.modeling.fleet.StopControlIfc
import ksl.modeling.fleet.StopInstruction
import ksl.modeling.fleet.Tour
import ksl.modeling.fleet.TourStop
import ksl.modeling.entity.KSLProcessBuilder

/**
 * Serve every stop, as soon as you reach it. The default, and what every fleet did before there was
 * a control to ask.
 */
class AlwaysServe : StopControlIfc {

    override suspend fun KSLProcessBuilder.instruct(
        vehicle: FleetVehicle,
        stop: TourStop,
        tour: Tour
    ): StopInstruction = StopInstruction.Serve()

    override fun toString(): String = "AlwaysServe"
}

/**
 * Hold at each stop until its scheduled departure, measured from the start of the cycle.
 *
 * A timetable, in the form a route service is usually specified in: not a wall-clock list of
 * departure times but an elapsed time from the start of the round, which is what makes the same
 * table serve every vehicle running the line whatever time it set out. A vehicle running early
 * waits; a vehicle running late does not, because an instant already past does not hold anything —
 * which is what a late service does, and is why the lateness shows up in the cycle time rather than
 * being smoothed away.
 *
 * Stops not in the table are served without holding.
 *
 * @param departures how long after the cycle began each stop may be left
 */
class TimetableControl(
    private val departures: Map<Stop, Double>
) : StopControlIfc {

    init {
        require(departures.values.all { it >= 0.0 }) {
            "A scheduled departure is an elapsed time from the start of the cycle and cannot be " +
                    "negative."
        }
    }

    override suspend fun KSLProcessBuilder.instruct(
        vehicle: FleetVehicle,
        stop: TourStop,
        tour: Tour
    ): StopInstruction {
        val served = stop.action.servesStop ?: return StopInstruction.Serve()
        val offset = departures[served] ?: return StopInstruction.Serve()
        return StopInstruction.Serve(departNotBefore = tour.startedAt + offset)
    }

    override fun toString(): String = "TimetableControl(${departures.size} timed stops)"
}

/**
 * Ask the dispatcher, and do what it says.
 *
 * The driver on the radio. It consumes an instruction the controller left with
 * [ksl.modeling.fleet.Dispatcher.instruct] and serves normally when there is none, which keeps the
 * number of deciders at one: the controller decided, and the ask merely collects. That is the same
 * shape a pending interruption has, and for the same reason.
 *
 * It answers without consuming simulated time. A control that needs a decision epoch, an
 * acknowledgement delay, or a radio queue is a subclass that suspends before returning.
 */
open class DispatcherStopControl : StopControlIfc {

    override suspend fun KSLProcessBuilder.instruct(
        vehicle: FleetVehicle,
        stop: TourStop,
        tour: Tour
    ): StopInstruction =
        vehicle.system.dispatcher.takeInstruction(vehicle) ?: StopInstruction.Serve()

    override fun toString(): String = "DispatcherStopControl"
}
