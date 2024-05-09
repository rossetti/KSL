package ksl.controls.experiments

interface DesignPointIteratorIfc : Iterator<DesignPoint> {

    val design: ExperimentalDesignIfc

    val factors
        get() = design.factors.values.toSet()

    val numFactors
        get() = design.numFactors

    /**
     *  The number of design points presented
     */
    val count: Int

    /**
     *  The last presented design point
     */
    val last: DesignPoint?

    /**
     *  A new iterator starting at the first point
     */
    fun newInstance() : DesignPointIteratorIfc
}