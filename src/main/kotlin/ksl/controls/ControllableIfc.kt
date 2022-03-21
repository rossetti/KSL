package ksl.controls

/** Implementors of this interface should be able to return an instance of
 *  the Controls class and should be able to take in an instance of Controls
 *  and use it correctly to set the internal state of the implementation.
 *
 *
 */
interface ControllableIfc {

    /**
     *  A control is something that can be used to control the functioning of
     *  the implementor
     */
    var controls: Controls?

}