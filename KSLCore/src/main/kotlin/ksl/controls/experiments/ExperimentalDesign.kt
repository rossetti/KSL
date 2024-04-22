package ksl.controls.experiments

import ksl.utilities.Identity

class ExperimentalDesign(
    factors: Set<Factor>,
    name: String? = null,
) : Identity(name), ExperimentalDesignIfc {

    init {
        require(factors.isNotEmpty()) { "At least one factor must be defined" }
    }

    override var defaultNumReplications: Int = 1
        set(value) {
            require(value > 0) { "Default number of replications must be greater than 0" }
            field = value
        }

    override val factors: Map<String, Factor> = factors.associateBy { it.name }

    override val factorNames: List<String> = factors.map { it.name }

    private val myDesignPoints = mutableListOf<DesignPoint>()

    /**
     *
     */
    fun addDesignPoint(
        settings: Map<Factor, Double>,
        numReps: Int = defaultNumReplications
    ) : DesignPoint {
        require(factors.values.containsAll(settings.keys)) { "The supplied factors do not match the design's factors" }
        val num = myDesignPoints.size + 1
        val dp = DesignPoint(this,num, settings, numReps)
        myDesignPoints.add(dp)
        return dp
    }

    override fun iterator(): Iterator<DesignPoint> {
        return myDesignPoints.iterator()
    }

    override fun designIterator(replications: Int): DesignPointIteratorIfc {
        return DesignPointIterator(replications)
    }

    /**
     *  This iterator should present each design point
     *  until all points in the design have been presented.
     *  @param defaultNumReplications the number of replications for the design points.
     *  Must be greater or equal to 1.
     */
    private inner class DesignPointIterator(val numReps: Int) : DesignPointIteratorIfc {

        private val itr = myDesignPoints.iterator()

        override var count: Int = 0
            private set

        override var last: DesignPoint? = null
            private set

        override fun hasNext(): Boolean {
            return itr.hasNext()
        }

        override fun next(): DesignPoint {
            count++
            val dp = itr.next()
            dp.numReplications = numReps
            last = dp
            return dp
        }

    }
}