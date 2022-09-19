package ksl.modeling.variable

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

interface ResponseIfc: ValueIfc, PreviousValueIfc, TimeOfChangeIfc, PreviousTimeOfChangeIfc, DefaultReportingOptionIfc

interface TimeWeightedIfc : ResponseIfc, WeightIfc, InitialValueIfc

interface CounterIfc: ResponseIfc, InitialValueIfc, AcrossReplicationStatisticIfc

interface VariableIfc : ValueIfc, PreviousValueIfc, InitialValueIfc, TimeOfChangeIfc, PreviousTimeOfChangeIfc