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

import ksl.utilities.random.rvariable.RVariableIfc

/**
 * What a vehicle's failures are measured against.
 *
 * The choice is not decoration. A fleet with long quiet periods fails a materially different number
 * of times by the clock than by the hours it actually worked, and a subsystem offering only one of
 * the two would be making that modelling decision on the modeller's behalf without saying so.
 */
enum class FailureBasis {

    /** Elapsed simulated time, whatever the vehicle was doing. Wall-clock ageing. */
    CALENDAR_TIME,

    /**
     * Time the vehicle was anything other than idle: moving, blocked, loading, unloading.
     * Hours in service rather than hours on the wall.
     */
    OPERATING_TIME,

    /** Ground covered, in the network's own length units. Wear that goes with mileage. */
    DISTANCE_TRAVELLED,

    /** Tasks the vehicle has finished. Wear that goes with duty cycles. */
    TASKS_COMPLETED
}

/**
 * When a vehicle fails, and how long it takes to put right.
 *
 * A failure is due once the chosen [basis] quantity has advanced by a draw from [betweenFailures]
 * since the last repair. Both clock-based and usage-based failures are that one statement with a
 * different basis, which is why there is one class here and not three.
 *
 * **A failure is noticed at the vehicle's next check point, not at the instant it becomes due.**
 * The basis quantities advance continuously and none of them has events of its own, so a failure
 * accrues silently and fires either at the next zone boundary or at the end of the current tour,
 * whichever the vehicle reaches first. That is the same rule the battery uses for exhaustion, and
 * for the same reason: a zone boundary is the only place a vehicle can be stopped without leaving
 * a claimed zone with no arrival.
 *
 * A vehicle parked with nothing to do therefore does not fail while parked. It fails at the first
 * boundary of its next journey, carrying whatever failures came due while it stood there. For a
 * fleet that is busy this is a rounding difference; for one that is idle most of the time it is
 * not, and `CALENDAR_TIME` on such a fleet should be read as "failures that had become due by the
 * time the vehicle next worked".
 *
 * **A failure does not revoke the vehicle's assignment.** The vehicle keeps its load, is repaired
 * where it stands, and resumes the tour it was running from the stop it had reached. Anything else
 * would mean a broken-down vehicle put its load back on the board while still holding it.
 *
 * @param basis what the threshold is measured against
 * @param betweenFailures how much of the basis quantity passes between failures, in that basis's
 *   own units -- time for the two clock bases, length for distance, a count for tasks
 * @param repairTime how long a repair takes, in model time units
 */
class FailureModel(
    val basis: FailureBasis,
    val betweenFailures: RVariableIfc,
    val repairTime: RVariableIfc
) {

    override fun toString(): String =
        "FailureModel(basis=$basis, betweenFailures=$betweenFailures, repairTime=$repairTime)"

    companion object {

        /**
         * Failures that come with the passage of time.
         *
         * @param basis [FailureBasis.OPERATING_TIME] by default, because a vehicle that is not
         *   working is usually not wearing. Pass [FailureBasis.CALENDAR_TIME] for a fleet whose
         *   failures are about age rather than about use.
         */
        @JvmStatic
        @JvmOverloads
        fun clockBased(
            timeBetweenFailures: RVariableIfc,
            repairTime: RVariableIfc,
            basis: FailureBasis = FailureBasis.OPERATING_TIME
        ): FailureModel {
            require(basis == FailureBasis.OPERATING_TIME || basis == FailureBasis.CALENDAR_TIME) {
                "A clock-based failure model measures time, so its basis must be OPERATING_TIME or " +
                        "CALENDAR_TIME, but was $basis."
            }
            return FailureModel(basis, timeBetweenFailures, repairTime)
        }

        /** Failures that come with duty cycles: one every so many completed tasks. */
        @JvmStatic
        fun usageBased(tasksBetweenFailures: RVariableIfc, repairTime: RVariableIfc): FailureModel =
            FailureModel(FailureBasis.TASKS_COMPLETED, tasksBetweenFailures, repairTime)

        /** Failures that come with mileage. */
        @JvmStatic
        fun distanceBased(
            distanceBetweenFailures: RVariableIfc,
            repairTime: RVariableIfc
        ): FailureModel = FailureModel(FailureBasis.DISTANCE_TRAVELLED, distanceBetweenFailures, repairTime)
    }
}
