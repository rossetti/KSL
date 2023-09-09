/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.variable

import ksl.utilities.IdentityIfc
import ksl.utilities.Interval
import ksl.utilities.PreviousValueIfc
import ksl.utilities.observers.DoublePairEmitterIfc

/**
 * This file defines small interfaces for use with variables
 *
 */

/**
 *  General property for getting and setting a value
 */
interface ValueIfc {
    val value: Double
}

interface LastValueIfc {
    val lastValue: Double
}

/**
 *  General property for getting (and setting) an initial value
 */
interface InitialValueIfc {
    val initialValue: Double
}

/**
 *  General property for getting (and setting) a weight associated with a value
 */
interface WeightIfc {
    val weight: Double
}

interface TimeOfChangeIfc {
    val timeOfChange: Double
}

interface PreviousTimeOfChangeIfc {
    val previousTimeOfChange: Double
}

interface RangeIfc {
    val domain: Interval
}

interface ResponseIfc : IdentityIfc, ValueIfc, PreviousValueIfc, TimeOfChangeIfc, PreviousTimeOfChangeIfc,
    DefaultReportingOptionIfc, RangeIfc, DoublePairEmitterIfc

interface TimeWeightedIfc : ResponseIfc, WeightIfc, VariableIfc

interface CounterIfc : ResponseIfc, InitialValueIfc, AcrossReplicationStatisticIfc

interface VariableIfc : IdentityIfc, ValueIfc, PreviousValueIfc, TimeOfChangeIfc, PreviousTimeOfChangeIfc,
    InitialValueIfc, RangeIfc