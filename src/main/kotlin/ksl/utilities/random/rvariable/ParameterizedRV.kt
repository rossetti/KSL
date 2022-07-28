package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc

/**
 * @param stream the source of the randomness
 */
abstract class ParameterizedRV (stream: RNStreamIfc, name: String? = null) : RVariable(stream, name), RVParametersIfc