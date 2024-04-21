package ksl.controls.experiments

import ksl.utilities.KSLArrays

class TwoLevelFactorialDesign(
    factors: Set<TwoLevelFactor>,
    name: String? = null
) : FactorialDesign(factors, name){

    /**
     *  This iterator should present each design point
     *  until all points in the design have been presented.
     */
    private inner class HalfFractionIterator : DesignPointIteratorIfc {
        override var count: Int = 0
            private set

        override var last: DesignPoint? = null
            private set

        override fun hasNext(): Boolean {
            return count < numDesignPoints
        }

        override fun next(): DesignPoint {
            count++
            last = designPoint(count)
          //  last!!.codedValues()
            return last!!
        }

    }
}