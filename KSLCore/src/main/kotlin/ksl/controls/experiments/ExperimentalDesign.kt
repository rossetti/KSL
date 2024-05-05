package ksl.controls.experiments

import ksl.utilities.Identity

class ExperimentalDesign(
    factors: Set<Factor>,
    name: String? = null,
//    numReps: Int = 1
) : Identity(name), ExperimentalDesignIfc {

    init {
        require(factors.isNotEmpty()) { "At least one factor must be defined" }
    }

    override val factors: Map<String, Factor> = factors.associateBy { it.name }

    override val factorNames: List<String> = factors.map { it.name }

    private val myDesignPoints = mutableListOf<DesignPoint>()

    /**
     *  Clears all the design points from the design.
     */
    fun clearDesignPoints() {
        myDesignPoints.clear()
    }

    /**
     *  Creates a design point and adds it to the design.
     *  @param values the values to assign to the factors, ordered by factor name
     *  @param numReps the number of replications for the design point, must be more than 0
     */
    fun addDesignPoint(
        values: DoubleArray,
        numReps: Int = 1
    ): DesignPoint {
        require(values.size == factorNames.size) { "The number of values must be ${factorNames.size}." }
        val settings = mutableMapOf<Factor, Double>()
        for ((i, fn) in factorNames.withIndex()) {
            val f = factors[fn]!!
//TODO            require(f.isValid(values[i])){"The supplied value (${values} is invalid for factor ${f.name} with interval ${f.interval}"}
            settings[f] = values[i]
        }
        return addDesignPoint(settings, numReps)
    }

    /**
     *  Creates a design point and adds it to the design.
     *  @param settings the factors in the settings must be in the design
     *  @param numReps the number of replications for the design point, must be more than 0
     */
    fun addDesignPoint(
        settings: Map<Factor, Double>,
        numReps: Int = 1
    ): DesignPoint {
        require(factors.values.containsAll(settings.keys)) { "The supplied factors do not match the design's factors" }
        val num = myDesignPoints.size + 1
        val dp = DesignPoint(this, num, settings, numReps)
        myDesignPoints.add(dp)
        return dp
    }

    override fun iterator(): Iterator<DesignPoint> {
        return myDesignPoints.iterator()
    }

    override fun designIterator(replications: Int?): DesignPointIteratorIfc {
        return DesignPointIterator(replications)
    }

    /**
     *  This iterator should present each design point
     *  until all points in the design have been presented.
     *  @param numReps the number of replications for the design points.
     *  If not null, it must be greater or equal to 1. If null, the design point's
     *  current number of replications is used.
     */
    private inner class DesignPointIterator(val numReps: Int? = null) : DesignPointIteratorIfc {

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
            if ((numReps != null) && (numReps > 0)) {
                dp.numReplications = numReps
            }
            last = dp
            return dp
        }

    }
}