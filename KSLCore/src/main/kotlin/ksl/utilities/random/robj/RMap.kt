package ksl.utilities.random.robj

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 *  Permits random sampling of the elements of the map.
 *  Elements are randomly sampled with equal probability based on the
 *  number of element in the map. There must be at least one element
 *  to permit random sampling.
 */
class RMap<K, V>(
    private val map: Map<K, V>,
    streamNum: Int = 0,
    private val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
) : Map<K, V> by map, RElementIfc<V>, RElementInstanceIfc<V>  {

    private val rnStream: RNStreamIfc = streamProvider.rnStream(streamNum)

    override val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    private val myList: List<K>

    init {
        require(map.isNotEmpty()) { "To randomly sample from a map, there must be at least one element" }
        myList = map.keys.toList()
    }

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): RElementIfc<V> {
        return RMap(map.toMap(), streamNum, rnStreamProvider)
    }

    /**
     *  Selected a value equally likely from the map
     */
    override val randomElement: V
        get() {
            require(this.isNotEmpty()) { "Cannot draw a random element from an empty map" }
            return map[myList[rnStream.randInt(0, myList.size - 1)]]!!
        }

    override var advanceToNextSubStreamOption: Boolean
        get() = rnStream.advanceToNextSubStreamOption
        set(value) {
            rnStream.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = rnStream.resetStartStreamOption
        set(value) {
            rnStream.resetStartStreamOption = value
        }

    override fun resetStartStream() {
        rnStream.resetStartStream()
    }

    override fun resetStartSubStream() {
        rnStream.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        rnStream.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = rnStream.antithetic
        set(value) {
            rnStream.antithetic = value
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
    streamNum: Int = 0,
    private val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
) : Map<K, V> by map, RElementIfc<V>, RElementInstanceIfc<V>  {

    private val myList: DEmpiricalList<K>

    private val rnStream: RNStreamIfc = streamProvider.rnStream(streamNum)

    override val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    init {
        require(map.isNotEmpty()) { "The supplied map must have at least 1 element." }
        require(KSLRandom.isValidCDF(theCDF)) { "The supplied cdf array is not a valid cdf" }
        require(map.size == theCDF.size) { "The number of keys must be equal to the number of probabilities." }
        myList = DEmpiricalList(map.keys.toList(), theCDF)
    }

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): RElementIfc<V> {
        return REmpiricalMap(map.toMap(), myList.cdf, streamNum, rnStreamProvider)
    }

    /**
     *  Selected a value based on the supplied CDF from the map
     */
    override val randomElement: V
        get() = map[myList.randomElement]!!

    override var advanceToNextSubStreamOption: Boolean
        get() = rnStream.advanceToNextSubStreamOption
        set(value) {
            rnStream.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = rnStream.resetStartStreamOption
        set(value) {
            rnStream.resetStartStreamOption = value
        }

    override fun resetStartStream() {
        rnStream.resetStartStream()
    }

    override fun resetStartSubStream() {
        rnStream.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        rnStream.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = rnStream.antithetic
        set(value) {
            rnStream.antithetic = value
        }
}