package ksl.simopt.evaluator

/**
 *  A class to assist with capturing data from a solution.
 *  @param id the identifier of the solution
 *  @param dataType the type of data in ("solution", "objectiveFunction", "responseEstimate", "input")
 *  @param subType a string to assist with identifying the data type
 *  @param dataName a string representing the name of the data
 *  @param dataValue the value associated with the named data
 */
data class SolutionData(
    val id: Int,
    val dataType: String,
    val subType: String?,
    val dataName: String,
    val dataValue: Double
)