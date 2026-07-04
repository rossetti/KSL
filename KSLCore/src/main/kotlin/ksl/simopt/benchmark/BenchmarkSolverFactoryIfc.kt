package ksl.simopt.benchmark

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver

/**
 *  Creates a fresh solver instance for one benchmark cell, bound to the cell's problem
 *  definition and private evaluator.
 *
 *  This is the problem-agnostic counterpart of the concurrent substrate's
 *  `ksl.simopt.solvers.concurrent.SolverFactoryIfc`: a solver case must be able to serve
 *  EVERY problem in an experiment, so the problem definition is an argument here rather
 *  than something the factory closes over. The benchmark experiment binds the factory to
 *  each problem (and wraps it with the budget criterion) before handing it to the
 *  concurrent runner.
 *
 *  The substrate factory's contract carries over: return a NEW solver instance on every
 *  call, bind it to the supplied evaluator only, and let the solver keep its own fresh
 *  stream provider (the solver constructors' default). Implementations are invoked on
 *  worker threads, so anything they capture must be safe to read concurrently.
 */
fun interface BenchmarkSolverFactoryIfc {

    /**
     *  Creates a fresh solver for one benchmark cell.
     *
     *  @param problemDefinition the problem the cell solves; the same instance the
     *  cell's evaluator serves
     *  @param evaluator the cell's private evaluator; the created solver must use it
     *  exclusively
     *  @param memberIndex the 0-based index of the cell within the problem's concurrent
     *  run; useful for index-dependent configuration
     *  @param name the cell's label; implementations should pass it to the solver so
     *  traces, logs, and results identify the cell
     *  @return the newly created solver
     */
    fun create(
        problemDefinition: ProblemDefinition,
        evaluator: EvaluatorIfc,
        memberIndex: Int,
        name: String
    ): Solver
}
