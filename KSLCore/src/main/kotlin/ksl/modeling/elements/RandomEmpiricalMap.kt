package ksl.modeling.elements

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.RElementIfc
import ksl.utilities.random.robj.REmpiricalMap

/**
 *  Allows random selection of elements from the map. The supplied map
 *  must have at least one element. The supplied array must represent a valid
 *  cumulative distribution function over the same number of elements as in the map.
 */
class RandomEmpiricalMap <K, V>(
    parent: ModelElement,
    private val rMap : REmpiricalMap<K, V>,
    name: String? = null
): ModelElement(parent, name), Map<K, V> by rMap, RElementIfc<V> by rMap {

    //TODO how to ensure rMap uses the model's stream provider

    init {
        warmUpOption = false
    }

    constructor(
        parent: ModelElement,
        map: Map<K, V>,
        theCDF: DoubleArray,
        streamNum: Int = 0,
        name: String? = null
    ) : this(parent, REmpiricalMap(map, theCDF, streamNum, parent.streamProvider), name)

}