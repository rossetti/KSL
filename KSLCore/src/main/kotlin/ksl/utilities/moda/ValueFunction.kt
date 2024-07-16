package ksl.utilities.moda

/**
 *  A value function maps values from some domain to
 *  the value range of [0.0, 1.0], where 0.0 implies no value
 *  and 1.0 implies maximal value.
 */
fun interface ValueFunctionIfc {

    fun value(score: Score): Double

}

/**
 *  A value function maps values from some metric domain to
 *  the value range of [0.0, 1.0], where 0.0 implies no value
 *  and 1.0 implies maximal value.
 */
abstract class ValueFunction() : ValueFunctionIfc