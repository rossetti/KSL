package ksl.controls.experiments

interface DesignPointIteratorIfc : Iterator<DesignPoint> {
    /**
     *  The number of design points presented
     */
    val count: Int

    /**
     *  The last presented design point
     */
    val last: DesignPoint?
}