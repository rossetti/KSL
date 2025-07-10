package ksl.modeling.elements

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.RElementIfc
import ksl.utilities.random.robj.RMap

/**
 *  Allows random selection of elements from the map. The supplied map
 *  must have at least one element
 */
class RandomMap<K, V> private constructor(
    parent: ModelElement,
    private val rMap: RMap<K, V>,
    name: String? = null
): ModelElement(parent, name), Map<K, V> by rMap, RElementIfc<V> by rMap, RandomElementIfc {

    init {
        warmUpOption = false
    }
    /**
     *  Allows randomly selecting with equal probability from the elements of the map.
     *  @param parent the parent of this element
     *  @param map the elements in the map
     *  @param streamNum the stream number to use from the provider. The default is 0, which
     *  is the next stream.
     *  @param name the optional name of the model element
     */
    @JvmOverloads
    @Suppress("unused")
    constructor(
        parent: ModelElement,
        map: Map<K, V>,
        streamNum: Int = 0,
        name: String? = null
    ) : this(parent, RMap(map, streamNum, parent.streamProvider), name)

}