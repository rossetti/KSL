package ksl.modeling.variable

/**
 *  An action that can occur when a counter hits its limit
 */
fun interface CounterActionIfc {

    fun action(counter: Counter)
}