package jsl.simulation

/**
 * An abstract class that implements EventActionIfc with a JSLEvent
 * message type of Any.
 */
abstract class EventAction : EventActionIfc<Any?> {
    abstract override fun action(event: JSLEvent<Any?>)
}