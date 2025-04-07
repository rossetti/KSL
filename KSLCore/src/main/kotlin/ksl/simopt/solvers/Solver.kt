package ksl.simopt.solvers

import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition

//TODO needs a lot more work
abstract class Solver(
    val problemDefinition: ProblemDefinition,
    val solverRunner: SolverRunner
){

    abstract fun instance(): Solver

    abstract fun initialize()

    abstract fun inputsForEvaluation() : List<DoubleArray>

    abstract fun evaluatedSolutions(solutions: List<Solution>)
}