package ksl.modeling.supplychain

/**
 * A [DemandFillerIfc] that drives received demands through its own
 * internal flow and does **not** need an external caller (the
 * storage-facility clone router, for example) to invoke
 * [DemandFillerIfc.fillDemand] on demands sent to it.
 *
 * Implementers handle the full receive → process → fill → ship cycle
 * inside their own `receive(...)` override. The canonical implementer
 * is `ksl.modeling.supplychain.facility.CrossDockFacility`; a future
 * production facility that consumes raw-material demands and runs an
 * internal recipe would also implement this.
 *
 * @see ksl.modeling.supplychain.facility.StorageFacilityAbstract
 */
interface RoutesDemandsInternally : DemandFillerIfc
