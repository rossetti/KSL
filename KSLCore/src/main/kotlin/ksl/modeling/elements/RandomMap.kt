package ksl.modeling.elements

import ksl.simulation.ModelElement
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.robj.RElementIfc
import ksl.utilities.random.robj.RMap
import ksl.utilities.random.rvariable.KSLRandom

class RandomMap<K, V>(
    parent: ModelElement,
    private val map: Map<K, V>,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
): ModelElement(parent, name), Map<K, V> by map, RElementIfc<V> {

    private val rMap : RMap<K, V> = RMap(map, stream)

    init {
        warmUpOption = false
        //TODO can this be moved into model? if so, where (cannot be in addToModelElementMap()) because that is in constructor
        // of the model element, which is called before this init block. this init block is called after the element has
        // been added to the model, upon creation of the element
        model.addStream(rMap.rnStream)
        RNStreamProvider.logger.info { "Initialized RandomMap(id = $id, name = ${this.name}) with stream id = ${rnStream.id}" }
    }

    constructor(
        parent: ModelElement,
        map: Map<K, V>,
        streamNum: Int,
        name: String? = null
    ) : this(parent, map, KSLRandom.rnStream(streamNum), name)

    override val randomElement: V
        get() = rMap.randomElement

    /**
     *  The random number stream for the current replication based on the
     *  current setting of property randomSource.  If the underlying stream
     *  is changed, the change will only be in effect for the current replication and
     *  no stream control will take place based on the model's control of streams.
     */
    override var rnStream: RNStreamIfc
        get() = rMap.rnStream
        set(value) {
            model.removeStream(rMap.rnStream)
            rMap.rnStream = value
            model.addStream(rnStream)
        }
}