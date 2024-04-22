package ksl.controls.experiments

interface DesignPointIteratorIfc : Iterator<DesignPoint> {

    /**
     *  The factors associated with the design point iterator.
     */
    val factors: List<Factor>

    /**
     *  The number of design points presented.
     */
    val count: Int

    /**
     *  The last presented design point.
     */
    val last: DesignPoint?
}