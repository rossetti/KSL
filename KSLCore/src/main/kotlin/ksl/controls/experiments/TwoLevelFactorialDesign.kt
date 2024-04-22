package ksl.controls.experiments

import ksl.utilities.KSLArrays
import ksl.utilities.product

class TwoLevelFactorialDesign(
    factors: Set<TwoLevelFactor>,
    name: String? = null
) : FactorialDesign(factors, name){

    /**
     *  This iterator should present each design point
     *  until all points in the design have been presented.
     */
    private inner class HalfFractionIterator(val half: Double = 1.0) : DesignPointIteratorIfc {
        init {
            require((half == 1.0) || (half == -1.0)) { "The half fraction must be 1.0 or -1.0"}
        }
        // use the main iterator to go through all point
        private val itr = iterator() as DesignPointIteratorIfc

        override val count: Int
            get() = itr.count

        override val last: DesignPoint?
            get() = itr.last

        override fun hasNext(): Boolean {
            return itr.hasNext()
        }

        override fun next(): DesignPoint {
            // get the next design point
            var next: DesignPoint = itr.next()
//            do {
//
//            }
            TODO("Not implemented yet")

//            var found = false
//            while (!found) {
//                next = itr.next()
//                if (next.codedValues().product() == half){
//                    found = true
//                }
//            }
//
//            return next
        }
    }
}