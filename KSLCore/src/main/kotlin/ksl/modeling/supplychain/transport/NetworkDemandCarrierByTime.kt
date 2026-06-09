package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Demand carrier that maps each (supplier, customer) edge to its own
 * [TransportDelay]. Aggregate statistics across every edge are
 * collected by an internal [AggregateTransportResponse].
 *
 * Default behavior preserves the Java carrier's fallback semantics
 * via capability interfaces: when no [TransportDelay] is configured
 * for a (supplier, customer) pair, the carrier permits zero-delay
 * transport if the customer is an [ExternalDemandConsumer] (via
 * [demandGeneratorImmediateTransportFlag]) or the supplier is an
 * [ExternalDemandSupplier] (via
 * [externalSupplierImmediateTransportFlag]); both flags default true.
 * Java tested concrete classes (`DemandGenerator` and
 * `LeadTimeDemandFiller`); the Kotlin port tests their capabilities
 * so future external endpoint types slot in without editing the
 * carrier.
 *
 * @param parent the parent model element
 * @param name optional model-element name
 *
 * @see sc.transportlayer.NetworkDemandCarrierByTime
 */
open class NetworkDemandCarrierByTime @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
) : DemandCarrierAbstract(parent, name) {

    private val myTransportTimes:
        MutableMap<DemandFillerIfc, MutableMap<DemandSenderIfc, TransportDelay>> =
        linkedMapOf()

    private val myAggResponse: AggregateTransportResponse =
        AggregateTransportResponse(this, baseName = this.name)

    /** Aggregate network-wide transport statistics. */
    val aggregateTransportResponse: AggregateTransportResponse
        get() = myAggResponse

    /**
     * When true (default), demands whose customer is a
     * [DemandGenerator] and that lack an explicit (supplier, customer)
     * edge are shipped and delivered immediately. When false, the
     * carrier throws [NoCarrierOptionException].
     */
    var demandGeneratorImmediateTransportFlag: Boolean = true

    /**
     * When true (default), demands whose supplier is a
     * [LeadTimeDemandFiller] and that lack an explicit (supplier,
     * customer) edge are shipped and delivered immediately. When
     * false, the carrier throws [NoCarrierOptionException].
     */
    var externalSupplierImmediateTransportFlag: Boolean = true

    /** Returns the [TransportDelay] for the pair, or null if unset. */
    fun getTransportDelay(
        supplier: DemandFillerIfc,
        customer: DemandSenderIfc,
    ): TransportDelay? = myTransportTimes[supplier]?.get(customer)

    /** Configure a zero-delay (supplier, customer) edge. */
    fun setTransportTime(supplier: DemandFillerIfc, customer: DemandSenderIfc) {
        setTransportTime(supplier, customer, ConstantRV.ZERO)
    }

    /** Configure a constant-time (supplier, customer) edge. */
    fun setTransportTime(
        supplier: DemandFillerIfc,
        customer: DemandSenderIfc,
        time: Double,
    ) {
        setTransportTime(supplier, customer, ConstantRV(time))
    }

    /**
     * Configure a (supplier, customer) edge with the supplied
     * transport-time distribution. The first call creates and
     * registers a [TransportDelay]; later calls reuse the same
     * delay and swap its initial source.
     */
    fun setTransportTime(
        supplier: DemandFillerIfc,
        customer: DemandSenderIfc,
        time: RVariableIfc,
    ) {
        val existing = getTransportDelay(supplier, customer)
        if (existing != null) {
            existing.setInitialTransportTime(time)
            return
        }
        val edges = myTransportTimes.getOrPut(supplier) { linkedMapOf() }
        val td = TransportDelay(
            this,
            transportTime = time,
            name = "${supplier.name} : ${customer.name}",
        )
        edges[customer] = td
        myAggResponse.subscribeTo(td)
    }

    override fun transportDemand(demand: SupplyChainModel.Demand) {
        if (!canShip(demand)) {
            throw NoCarrierOptionException(
                buildString {
                    append("cannot ship ")
                    append(demand)
                    append(" from=")
                    append(demand.filler?.name)
                    append(" to=")
                    append(demand.demandSender?.name)
                },
            )
        }
        val supplier = demand.filler!!
        val customer = demand.demandSender!!
        val td = getTransportDelay(supplier, customer)
        if (td != null) {
            td.startDelay(demand)
            return
        }
        if (customer is ExternalDemandConsumer) {
            if (demandGeneratorImmediateTransportFlag) {
                demand.ship()
                demand.deliver()
            }
            return
        }
        if (supplier is ExternalDemandSupplier) {
            if (externalSupplierImmediateTransportFlag) {
                demand.ship()
                demand.deliver()
            }
        }
    }

    override fun canShip(demand: SupplyChainModel.Demand): Boolean {
        val supplier = demand.filler ?: return false
        val customer = demand.demandSender ?: return false
        if (getTransportDelay(supplier, customer) != null) return true
        if (customer is ExternalDemandConsumer) {
            return demandGeneratorImmediateTransportFlag
        }
        if (supplier is ExternalDemandSupplier) {
            return externalSupplierImmediateTransportFlag
        }
        return false
    }

    override fun toString(): String =
        "${this::class.simpleName} = $name"
}
