package ksl.utilities.random.robj

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 *
 */
class RMap<K, V>(
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    private val map: MutableMap<K, V> = mutableMapOf(),
) : MutableMap<K, V> by map, RElementIfc<V> {

    override var rnStream: RNStreamIfc = stream

    private val myList = mutableListOf<K>()

    override fun put(key: K, value: V): V? {
        myList.add(key)
        return map.put(key, value)
    }

    /**
     *  Selected a value equally likely from the map
     */
    override val randomElement: V
        get() = map[myList[rnStream.randInt(0, myList.size - 1)]]!!

}

class REmpiricalMap<K,V>(
    private val map: Map<K, V>,
    theCDF: DoubleArray,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
): Map<K, V> by map, RElementIfc<V> {

    private val myList: DEmpiricalList<K>
    override var rnStream: RNStreamIfc = stream

    init {
        require(KSLRandom.isValidCDF(theCDF)) { "The supplied cdf array is not a valid cdf" }
        require(map.size >= theCDF.size) { "The number of keys was less than the number of probabilities." }
        myList = DEmpiricalList(map.keys.toList(), theCDF)
    }

    /**
     *  Selected a value based on the supplied CDF from the map
     */
    override val randomElement: V
        get() = map[myList.randomElement]!!


}