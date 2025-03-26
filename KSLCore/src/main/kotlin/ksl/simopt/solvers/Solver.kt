package ksl.simopt.solvers

import ksl.simopt.evaluator.Solution

//TODO needs a lot more work
abstract class Solver {

    abstract fun instance(): Solver

    abstract fun initialize()

    abstract fun inputsForEvaluation() : List<DoubleArray>

    abstract fun evaluatedSolutions(solutions: List<Solution>)
}