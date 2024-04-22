package ksl.controls.experiments

import ksl.utilities.product

class TwoLevelFactorialDesign(
    factors: Set<TwoLevelFactor>,
    name: String? = null
) : FactorialDesign(factors, name){

    /**
     *  @param half indicates the half-fraction to iterate. 1.0 indicates the positive
     *  half-fraction and -1.0 the negative half-fraction. The default is 1.0
     */
    fun halfFractionIterator(half: Double = 1.0) : HalfFractionIterator {
        return HalfFractionIterator(half)
    }

    /**
     *  This iterator should present each design point in the associated half-fraction
     *  until all points in the half-fraction have been presented.
     */
    inner class HalfFractionIterator(val half: Double = 1.0) : DesignPointIteratorIfc {

        // The internal iterator for the points
        private val itr : Iterator<DesignPoint>
        init {
            require((half == 1.0) || (half == -1.0)) { "The half fraction must be 1.0 or -1.0"}
            // make the sequence and get the iterator
            val tmp = this@TwoLevelFactorialDesign.iterator()
            itr = tmp.asSequence().filter { it.codedValues().product() == half }.iterator()
        }

        override var count: Int = 0
            private set

        override var last: DesignPoint? = null
            private set

        override fun hasNext(): Boolean {
            return itr.hasNext()
        }

        override fun next(): DesignPoint {
            count++
            last = itr.next()
            return last!!
        }
    }
}