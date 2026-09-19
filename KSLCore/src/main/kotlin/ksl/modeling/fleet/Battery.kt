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

/**
 * A vehicle's energy store: how much it holds, how fast it empties, and how fast it fills.
 *
 * **Two drain rates, because a real vehicle has two.** Traction energy scales with distance and
 * stops when the vehicle stops. Hotel load -- controller, radio, lights, heating -- scales with
 * time and does not: a parked AGV still draws current. A model with one rate can represent either
 * of those, and gets the other wrong.
 *
 * The consequence is a hazard worth stating before the parameters: with [chargePerTime] above zero,
 * **a lightly loaded fleet strands itself**. Charge falls while vehicles wait for work, so the
 * configuration a modeller would assume is benign -- plenty of vehicles, not much to do -- is the
 * one that eventually loses every vehicle. Under distance-only depletion an idle fleet is safe. It
 * is not safe here, and that is the point of modelling the second rate at all.
 *
 * A per-state rate table -- one rate while moving loaded, another while blocked, another while idle
 * -- is the natural next refinement and is deliberately not offered. Two numbers can be measured.
 * Five invite calibration effort against data nobody has.
 *
 * **Charge is derived rather than stepped.** Nothing here schedules an event. The level is a
 * closed-form function of the vehicle's odometers, computed whenever it is asked for, which is why
 * adding a battery to a model does not change how many events its run takes.
 *
 * The units are the modeller's own throughout: charge may be amp-hours, kilowatt-hours, or a
 * percentage, as long as the four numbers agree with each other and with the network's length units
 * and the model's time units.
 *
 * @param capacity how much charge the battery holds when full. Must be > 0.
 * @param chargePerDistance charge drawn per unit of distance travelled -- traction. Must be >= 0.
 * @param chargePerTime charge drawn per unit of elapsed time, whatever the vehicle is doing --
 *   hotel load. Zero by default, which recovers a model with no idle draw exactly, so adding this
 *   class to a subsystem cannot silently change an existing model.
 * @param chargingRate charge added per unit of time while charging. Must exceed [chargePerTime]:
 *   a charger that cannot outpace the hotel load never fills the battery, and a model configured
 *   that way would wait forever rather than fail.
 * @param initialCharge what the battery holds at the start of every replication. Full by default.
 */
class Battery @JvmOverloads constructor(
    val capacity: Double,
    val chargePerDistance: Double,
    val chargePerTime: Double = 0.0,
    val chargingRate: Double = capacity,
    val initialCharge: Double = capacity
) {

    init {
        require(capacity > 0.0) { "A battery's capacity must be > 0.0, but was $capacity." }
        require(chargePerDistance >= 0.0) {
            "A battery's charge per unit distance must be >= 0.0, but was $chargePerDistance."
        }
        require(chargePerTime >= 0.0) {
            "A battery's charge per unit time must be >= 0.0, but was $chargePerTime."
        }
        // A battery that never empties is a battery that changes nothing, and a parameter that
        // changes nothing is the defect this subsystem has already had to fix once.
        require(chargePerDistance > 0.0 || chargePerTime > 0.0) {
            "A battery was given a charge per unit distance and a charge per unit time of zero, so " +
                    "it would never discharge and the vehicle carrying it would behave exactly as " +
                    "one with no battery at all. Give it a drain rate, or give the vehicle no battery."
        }
        require(chargingRate > chargePerTime) {
            "A battery was given a charging rate of $chargingRate and a charge per unit time of " +
                    "$chargePerTime. A charger must outpace the draw that continues while the " +
                    "vehicle is on it, or the battery never reaches full and the vehicle waits there " +
                    "for the rest of the run."
        }
        require(initialCharge in 0.0..capacity) {
            "A battery's initial charge must be between 0.0 and its capacity ($capacity), but was " +
                    "$initialCharge."
        }
    }

    /**
     * How much charge a journey of this distance, taking this long, would draw.
     *
     * Both terms, always. A reserve computed from distance alone is correct without idle draw and
     * wrong with it, and it is wrong in the dangerous direction: it under-reserves exactly when the
     * trip is slow, which is when a congested guide path makes it slow. See [ksl.modeling.fleet.policies.ChargeReservePolicy].
     */
    fun drawFor(distance: Double, duration: Double): Double =
        distance * chargePerDistance + duration * chargePerTime

    override fun toString(): String =
        "Battery(capacity=$capacity, perDistance=$chargePerDistance, perTime=$chargePerTime, " +
                "chargingRate=$chargingRate, initial=$initialCharge)"
}
