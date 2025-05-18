package ksl.modeling.elements

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.RElementIfc
import ksl.utilities.random.robj.RMap

/**
 *  Allows random selection of elements from the map. The supplied map
 *  must have at least one element
 */
class RandomMap<K, V>(
    parent: ModelElement,
    private val rMap: RMap<K, V>,
    name: String? = null
): ModelElement(parent, name), Map<K, V> by rMap, RElementIfc<V> by rMap, RandomElementIfc {

    //TODO how to ensure rMap uses the model's stream provider

    init {
        warmUpOption = false
    }

    constructor(
        parent: ModelElement,
        map: Map<K, V>,
        streamNumber: Int = 0,
        name: String? = null
    ) : this(parent, RMap(map, streamNumber, parent.streamProvider), name)

}