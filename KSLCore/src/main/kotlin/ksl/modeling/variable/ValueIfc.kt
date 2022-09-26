package ksl.modeling.variable

import ksl.utilities.IdentityIfc
import ksl.utilities.Interval
import ksl.utilities.PreviousValueIfc

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
    val range: Interval
}

interface ResponseIfc : IdentityIfc, ValueIfc, PreviousValueIfc, TimeOfChangeIfc, PreviousTimeOfChangeIfc,
    DefaultReportingOptionIfc, RangeIfc

interface TimeWeightedIfc : ResponseIfc, WeightIfc, VariableIfc

interface CounterIfc : ResponseIfc, InitialValueIfc, AcrossReplicationStatisticIfc

interface VariableIfc : IdentityIfc, ValueIfc, PreviousValueIfc, TimeOfChangeIfc, PreviousTimeOfChangeIfc,
    InitialValueIfc, RangeIfc