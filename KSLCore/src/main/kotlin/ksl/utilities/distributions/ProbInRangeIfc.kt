package ksl.utilities.distributions

interface ProbInRangeIfc {
    /**
     *  Computes the sum of the probabilities over the provided range.
     *  If the range is closed a..b then the end point b is included in the
     *  sum. If the range is open a..&ltb then the point b is not included
     *  in the sum.
     */
    fun probIn(range: IntRange): Double
}