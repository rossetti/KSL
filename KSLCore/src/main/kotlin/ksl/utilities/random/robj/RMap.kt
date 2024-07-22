package ksl.utilities.random.robj

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 *  Permits random sampling of the elements of the map.
 *  Elements are randomly sampled with equal probability based on the
 *  number of element in the map. There must be at least one element
 *  to permit random sampling.
 */
class RMap<K, V>(
    private val map: Map<K, V>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : Map<K, V> by map, RElementIfc<V> {

    override var rnStream: RNStreamIfc = stream

    private val myList: List<K>

    init {
        require(map.isNotEmpty()) { "To randomly sample from a map, there must be at least one element" }
        myList = map.keys.toList()
    }

    /**
     *  Selected a value equally likely from the map
     */
    override val randomElement: V
        get() {
            require(this.isNotEmpty()) { "Cannot draw a random element from an empty map" }
            return map[myList[rnStream.randInt(0, myList.size - 1)]]!!
        }
}

/**
 *  Permits random sampling of the elements of the map according to
 *  the supplied CDF over the keys. The number of elements in the CDF must
 *  be the same as the number of keys in the map. The map must have
 *  at least one element. The CDF array must be a valid cumulative
 *  probability distribution.
 */
class REmpiricalMap<K, V>(
    private val map: Map<K, V>,
    theCDF: DoubleArray,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : Map<K, V> by map, RElementIfc<V> {

    private val myList: DEmpiricalList<K>
    override var rnStream: RNStreamIfc = stream

    init {
        require(map.isNotEmpty()) { "The supplied map must have at least 1 element." }
        require(KSLRandom.isValidCDF(theCDF)) { "The supplied cdf array is not a valid cdf" }
        require(map.size == theCDF.size) { "The number of keys must be equal to the number of probabilities." }
        myList = DEmpiricalList(map.keys.toList(), theCDF)
    }

    /**
     *  Selected a value based on the supplied CDF from the map
     */
    override val randomElement: V
        get() = map[myList.randomElement]!!

}