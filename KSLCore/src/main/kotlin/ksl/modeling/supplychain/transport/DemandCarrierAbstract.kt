package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

import ksl.simulation.ModelElement

/**
 * Abstract base for [DemandCarrierIfc] implementations that need to
 * schedule events. Extends [ModelElement] so subclasses can use KSL's
 * `EventAction` pattern.
 *
 * @see sc.transportlayer.DemandCarrierAbstract
 */
abstract class DemandCarrierAbstract @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
) : ModelElement(parent, name), DemandCarrierIfc
