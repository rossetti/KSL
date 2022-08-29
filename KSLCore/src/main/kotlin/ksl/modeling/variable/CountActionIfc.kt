package ksl.modeling.variable

/**
 *  An action that can occur when a count hits its limit
 */
fun interface CountActionIfc {

    fun action(response: ResponseIfc)
}