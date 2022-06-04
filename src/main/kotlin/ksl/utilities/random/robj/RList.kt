package ksl.utilities.random.robj

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

abstract class RList<T>(val elements: MutableList<T>, stream: RNStreamIfc = KSLRandom.nextRNStream()) : RListIfc<T>,
    MutableList<T> by elements {

    override var rnStream: RNStreamIfc = stream

}