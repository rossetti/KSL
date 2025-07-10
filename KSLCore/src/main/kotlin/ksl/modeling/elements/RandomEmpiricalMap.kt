package ksl.modeling.elements

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.RElementIfc
import ksl.utilities.random.robj.REmpiricalMap

/**
 *  Allows random selection of elements from the map. The supplied map
 *  must have at least one element. The supplied array must represent a valid
 *  cumulative distribution function over the same number of elements as in the map.
 */
class RandomEmpiricalMap <K, V> private constructor(
    parent: ModelElement,
    private val rMap : REmpiricalMap<K, V>,
    name: String? = null
): ModelElement(parent, name), Map<K, V> by rMap, RElementIfc<V> by rMap {

    init {
        warmUpOption = false
    }

    /**
     *  Allows randomly selecting with equal probability from the elements of the map.
     *  @param parent the parent of this element
     *  @param map the elements in the map
     *  @param theCDF the CDF for the elements in the map
     *  @param streamNum the stream number to use from the provider. The default is 0, which
     *  is the next stream.
     *  @param name the optional name of the model element
     */
    @JvmOverloads
    @Suppress("unused")
    constructor(
        parent: ModelElement,
        map: Map<K, V>,
        theCDF: DoubleArray,
        streamNum: Int = 0,
        name: String? = null
    ) : this(parent, REmpiricalMap(map, theCDF, streamNum, parent.streamProvider), name)

}