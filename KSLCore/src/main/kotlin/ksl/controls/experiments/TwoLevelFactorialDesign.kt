package ksl.controls.experiments

import ksl.utilities.math.KSLMath
import kotlin.math.ln
import kotlin.math.roundToInt

class TwoLevelFactorialDesign @JvmOverloads constructor(
    factors: Set<TwoLevelFactor>,
    name: String? = null
) : FactorialDesign(factors, name) {

    /**
     *  @param half indicates the half-fraction to iterate. 1.0 indicates the positive
     *  half-fraction and -1.0 the negative half-fraction. The default is 1.0
     *  @param numReps the number of replications for the design points.
     *  Must be greater or equal to 1. If null, then the current value for the
     *  number of replications of each design point is used. Null is the default.
     */
    @JvmOverloads
    @Suppress("unused")
    fun halfFractionIterator(half: Double = 1.0, numReps: Int? = null): TwoLevelFractionalIterator {
        val rs = (1..numFactors).toList().toSet()
        return TwoLevelFractionalIterator(setOf(rs), numReps, half)
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
     *  @param numReps the number of replications for the design points.
     *  Must be greater or equal to 1. If null, then the current value for the
     *  number of replications of each design point is used. Null is the default.
     *  @param sign the sign of the generator 1.0 = I, -1.0 = -I. The default is 1.0.
     */
    @JvmOverloads
    @Suppress("unused")
    fun fractionalIterator(
        relation: Set<Set<Int>>,
        numReps: Int? = null,
        sign: Double = 1.0
    ): TwoLevelFractionalIterator {
        return TwoLevelFractionalIterator(relation, numReps, sign)
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
     *  @param numReps the number of replications for the design points.
     *  Must be greater or equal to 1. If null, then the current value for the
     *  number of replications of each design point is used. Null is the default.
     *  @param sign the sign of the generator 1.0 = I, -1.0 = -I. The default is 1.0.
     */
    inner class TwoLevelFractionalIterator @JvmOverloads constructor(
        private val relation: Set<Set<Int>>,
        numReps: Int? = null,
        val sign: Double = 1.0
    ): FactorialDesignIterator(numReps) {
        init{
            require((sign == 1.0) || (sign == -1.0)) { "The generator sign must be 1.0 or -1.0" }
        }

        // The internal iterator for the points
        private val itr: Iterator<DesignPoint>

        private val points: List<DesignPoint>

        init {
            // make the sequence and get the iterator
            val tmp = design.iterator()
            val filter = tmp.asSequence().filter { inDesignRelation(it, relation, sign) }
            // the points in the fraction
            points = filter.toList()
            itr = points.iterator()
        }

        /**
         *  The number of points to iterate through
         */
        val numPoints: Int
            get() = points.size

        /**
         *  For a 2^(k-p) factorial design, this is p. p=1 means half-fraction,
         *  p=2 means quarter fraction, etc.
         */
        val fraction : Int
            get() = numFactors - (ln(numPoints.toDouble())/ln(2.0)).roundToInt()

        override fun hasNext(): Boolean {
            return itr.hasNext()
        }

        override fun next(): DesignPoint {
            count++
            last = itr.next()
            if ((numReps != null) && (numReps > 1)){
                last!!.numReplications = numReps
            }
            return last!!
        }

        /**
         *  A new iterator starting at the first point
         */
        override fun newInstance() : TwoLevelFractionalIterator {
            return TwoLevelFractionalIterator(relation, numReps, sign)
        }
    }

    companion object {

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
        @JvmStatic
        @JvmOverloads
        fun inDesignWord(
            dp: DesignPoint,
            word: Set<Int>,
            sign: Double = 1.0
        ) : Boolean {
            require((sign == 1.0) || (sign == -1.0)) { "The generator sign must be 1.0 or -1.0" }
            require(word.size >= 2) {"The word must have at least 2 elements"}
            val numFactors = dp.settings.size
            require(word.size <= numFactors)
            {"The size of the word (${word.size} must be <= the number of factors ($numFactors)"}
            for (index in word) {
                require(index in 1..numFactors) { "The value $index in the word must be between 1 and $numFactors" }
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
        @JvmStatic
        @JvmOverloads
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
    }
}