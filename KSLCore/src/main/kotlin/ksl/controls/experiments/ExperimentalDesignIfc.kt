package ksl.controls.experiments

interface ExperimentalDesignIfc : Iterable<DesignPoint> {

    /**
     *  The factors associated with the design held by
     *  name.
     */
    val factors: Map<String, Factor>

    /**
     *  The names of the factors within a list.
     */
    val factorNames: List<String>

    /**
     *  Returns the name of the factor. The first factor is at k = 1
     *  @param k must be in 1 to number of factors
     */
    fun factorName(k: Int): String = factorNames[k - 1]

}