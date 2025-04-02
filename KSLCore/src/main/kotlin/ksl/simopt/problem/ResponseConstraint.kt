package ksl.simopt.problem

fun interface PenaltyFunctionIfc {

    fun penalty(iterationCounter: Double): Double
}

/**
 *  A response constraint represents a general constrain of the form E[G(x)] < c or E[G(x)] > c
 *  where G(x) is some response from the model that is a function of the model inputs.
 *
 *  @param responseName the name of the response in the model
 *  @param rhsValue the right-hand side value
 *  @param inequalityType the type of inequality (less-than or greater-than)
 *  @param penaltyFunction the function to compute the penalty for the constraint
 */
class ResponseConstraint(
    val responseName: String,
    var rhsValue: Double,
    val inequalityType: InequalityType = InequalityType.LESS_THAN,
    val penaltyFunction: PenaltyFunctionIfc
) {
    init {
        require(responseName.isNotBlank()) { "The response name cannot be blank" }
    }

    private val inequalityFactor: Double = if (inequalityType == InequalityType.LESS_THAN) 1.0 else -1.0

    val ltRHSValue: Double
        get() = inequalityFactor * rhsValue

    fun slack(v: Double): Double {
        return inequalityFactor * (rhsValue - v)
    }

    //TODO need to think about violations and penalties

    fun violation(v: Double): Double {
        return maxOf(0.0, -slack(v))
    }

    fun penalty(responseValue: Double, iterationCounter: Double): Double {
        return violation(responseValue) * penaltyFunction.penalty(iterationCounter)
    }
}
