package ksl.simopt.problem

data class InputConstraint(
    val equation: Map<String, Double>,
    val rhsValue: Double = 0.0,
    val inequalityType: InequalityType = InequalityType.LESS_THAN
) {

    fun isSatisfied(values: Map<String, Double>): Boolean {
        TODO("Not implemented yet!")
    }

    fun coefficients(inputNames: List<String>): List<Double> {
        TODO("Not implemented yet!")
    }
}