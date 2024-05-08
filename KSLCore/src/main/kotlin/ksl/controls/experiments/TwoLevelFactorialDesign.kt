package ksl.controls.experiments

import ksl.utilities.math.KSLMath
import ksl.utilities.product

class TwoLevelFactorialDesign(
    factors: Set<TwoLevelFactor>,
    name: String? = null
) : FactorialDesign(factors, name) {

    /**
     *  @param half indicates the half-fraction to iterate. 1.0 indicates the positive
     *  half-fraction and -1.0 the negative half-fraction. The default is 1.0
     */
    fun halfFractionIterator(half: Double = 1.0): HalfFractionIterator {
        return HalfFractionIterator(half)
    }

    /**
     *  This iterator should present each design point in the associated fractional design
     *  until all points in the fractional design have been presented.
     *
     *  Checks if the coded values of the design point are in the defining
     *  relation specified by the factor numbers stored in the relation set.
     *  Suppose the designing relation is I = 124 = 135 = 2345
     *  Then relation = setOf(setOf(1,2,4), setOf(1,3,5), setOf(2,3,4,5)).
     *
     *  The values in the words must be valid factor indices. That is
     *  If a design has 5 factors, then the indices must be in 1..5.
     *  With 1 referencing the first factor, 2 the 2nd, etc.
     *
     *  @param relation the set of words for the defining relation.
     *  @param sign the sign of the generator 1.0 = I, -1.0 = -I. The default is 1.0.
     */
    fun fractionalIterator(
        relation: Set<Set<Int>>,
        sign: Double = 1.0
    ): FractionalIterator {
        return FractionalIterator(relation, sign)
    }

    /**
     *  This iterator should present each design point in the associated half-fraction
     *  until all points in the half-fraction have been presented.
     */
    inner class HalfFractionIterator(val half: Double = 1.0) : DesignPointIteratorIfc {
        /**
         *  The fraction level (p) of the iterator, as in 2^(k-p)
         *  A half-fraction has p = 1, i.e. 2^(-1) = 1/2
         */
        val fraction = 1

        override val design = this@TwoLevelFactorialDesign
        // The internal iterator for the points
        private val itr: Iterator<DesignPoint>

        init {
            require((half == 1.0) || (half == -1.0)) { "The half fraction must be 1.0 or -1.0" }
            // make the sequence and get the iterator
            val tmp = this@TwoLevelFactorialDesign.iterator()
            itr = tmp.asSequence().filter {
                KSLMath.equal(it.codedValues().product(), half)
            }.iterator()
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

    /**
     *  Checks if the coded values of the design point are in the word
     *  specified by the factor numbers stored in the relation set.
     *  Suppose the word of the designing relation is 2345
     *  Then word = setOf(2,3,4,5).
     *  The values in the word set must be valid factor indices.
     *  If a design has 5 factors, then the indices must be in 1..5.
     *  With 1 referencing the first factor, 2 the 2nd, etc.
     *  @param dp the design point to check
     *  @param word the set of factor indices for the word of a defining relation. The
     *  word must have at least 3 elements and its size must be less than
     *  or equal to the number of factors.
     *  @param sign the sign of the generator 1.0 = I, -1.0 = -I. The default is 1.0.
     */
    fun inDesignWord(
        dp: DesignPoint,
        word: Set<Int>,
        sign: Double = 1.0
    ) : Boolean {
        require((sign == 1.0) || (sign == -1.0)) { "The generator sign must be 1.0 or -1.0" }
        require(word.size >= 2) {"The word must have at least 2 elements"}
        require(word.size <= dp.settings.size)
            {"The size of the word (${word.size} must be <= the number of factors (${dp.settings.size})"}
        for (index in word) {
            require(index in 1..factors.size) { "The value $index in the word must be between 1 and ${factors.size}" }
        }
        val cv = dp.codedValues()
        var p = 1.0
        for (index in word) {
            p = p * cv[index - 1]
        }
        //return p == sign
        return KSLMath.equal(p, sign)
    }

    /**
     *  Checks if the coded values of the design point are in the defining
     *  relation specified by the factor numbers stored in the relation set.
     *  Suppose the designing relation is I = 124 = 135 = 2345
     *  Then relation = setOf(setOf(1,2,4), setOf(1,3,5), setOf(2,3,4,5)).
     *
     *  The values in the words must be valid factor indices. That is
     *  If a design has 5 factors, then the indices must be in 1..5.
     *  With 1 referencing the first factor, 2 the 2nd, etc.
     *
     *  @param relation the set of words for the defining relation.
     *  @param sign the sign of the generator 1.0 = I, -1.0 = -I. The default is 1.0.
     */
    fun inDesignRelation(
        dp: DesignPoint,
        relation: Set<Set<Int>>,
        sign: Double = 1.0
    ) : Boolean {
        require((sign == 1.0) || (sign == -1.0)) { "The generator sign must be 1.0 or -1.0" }
        if (relation.isEmpty()){
            return false
        }
        for(word in relation){
            if (!inDesignWord(dp, word, sign)){
                return false
            }
        }
        return true
    }

    /**
     *  This iterator should present each design point in the associated fractional design
     *  until all points in the fractional design have been presented.
     *
     *  Checks if the coded values of the design point are in the defining
     *  relation specified by the factor numbers stored in the relation set.
     *  Suppose the designing relation is I = 124 = 135 = 2345
     *  Then relation = setOf(setOf(1,2,4), setOf(1,3,5), setOf(2,3,4,5)).
     *
     *  The values in the words must be valid factor indices. That is
     *  If a design has 5 factors, then the indices must be in 1..5.
     *  With 1 referencing the first factor, 2 the 2nd, etc.
     *
     *  @param relation the set of words for the defining relation.
     *  @param sign the sign of the generator 1.0 = I, -1.0 = -I. The default is 1.0.
     */
    inner class FractionalIterator(
        relation: Set<Set<Int>>,
        sign: Double = 1.0
    ) : DesignPointIteratorIfc {
        override val design : TwoLevelFactorialDesign = this@TwoLevelFactorialDesign

        // The internal iterator for the points
        private val itr: Iterator<DesignPoint>

        init {
            // make the sequence and get the iterator
            val tmp = this@TwoLevelFactorialDesign.iterator()
            itr = tmp.asSequence().filter { inDesignRelation(it, relation, sign) }.iterator()
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