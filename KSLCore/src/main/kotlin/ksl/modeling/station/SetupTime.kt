/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.station

import ksl.simulation.ModelElement

/**
 *  Determines the setup (changeover) time incurred before a station serves a
 *  QObject, as a function of the previously served type and the arriving type.
 *  This supports sequence-dependent setups common in manufacturing.
 */
fun interface SetupTimeIfc {
    /**
     *  The setup time before serving [toType], given the type the server was last
     *  configured for ([fromType], null if nothing has been served yet).
     */
    fun setupTime(fromType: Int?, toType: Int, qObject: ModelElement.QObject): Double
}

/**
 *  A fixed changeover setup: [setup] time is incurred whenever the type changes
 *  (including the first job), and zero when the next job is the same type as the
 *  previous one.
 */
class ChangeoverSetupTime(private val setup: Double) : SetupTimeIfc {
    init {
        require(setup >= 0.0) { "The setup time must be >= 0" }
    }

    override fun setupTime(fromType: Int?, toType: Int, qObject: ModelElement.QObject): Double =
        if (fromType == toType) 0.0 else setup
}

/**
 *  A sequence-dependent setup matrix: the setup before serving [toType] depends on
 *  the (fromType, toType) pair.
 *
 *  @param setups setup times keyed by (fromType, toType); missing pairs use [defaultSetup]
 *  @param initialSetups setup times for the first job, keyed by toType; missing uses [defaultSetup]
 *  @param defaultSetup the setup used when a pair (or initial type) is not listed
 */
class SequenceDependentSetupTime(
    setups: Map<Pair<Int, Int>, Double>,
    initialSetups: Map<Int, Double> = emptyMap(),
    private val defaultSetup: Double = 0.0
) : SetupTimeIfc {

    private val mySetups = setups.toMap()
    private val myInitialSetups = initialSetups.toMap()

    override fun setupTime(fromType: Int?, toType: Int, qObject: ModelElement.QObject): Double {
        if (fromType == null) return myInitialSetups[toType] ?: defaultSetup
        if (fromType == toType) return 0.0
        return mySetups[fromType to toType] ?: defaultSetup
    }
}
