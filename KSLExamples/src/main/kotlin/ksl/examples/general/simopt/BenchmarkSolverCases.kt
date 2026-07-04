package ksl.examples.general.simopt

import ksl.simopt.benchmark.BenchmarkSolverFactoryIfc
import ksl.simopt.benchmark.SolverCase
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule
import ksl.simopt.solvers.algorithms.CrossEntropySolver
import ksl.simopt.solvers.algorithms.RSplineSolver
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simopt.solvers.algorithms.SimulatedAnnealing
import ksl.simopt.solvers.algorithms.StochasticHillClimber

/**
 *  The standard solver-case registry for benchmark studies: the paper's "vanilla"
 *  configurations, one named case per algorithm family, each at its LIBRARY DEFAULTS
 *  (the solver constructors' own default parameter values). This file replaces the old
 *  SolverType enums and comment-toggled solverFactory functions — a study picks cases
 *  from this list (or adds variants, which are one-line additions) instead of editing
 *  a when-expression.
 *
 *  Contract reminders (see the KDoc on `ksl.simopt.benchmark.SolverCase`):
 *  - Each factory returns a FRESH solver bound to the supplied evaluator with its own
 *    solver-side streams (the constructors' fresh-provider default).
 *  - Cases are budget-agnostic: the benchmark experiment attaches the replication
 *    budget and the iteration ceiling; a maxIterations set here would be overridden.
 *  - The configuration that actually ran is captured from each case's first created
 *    instance into the results database (tblSolverCaseParameter) — what ran is
 *    recorded, not assumed.
 *
 *  Notes per case:
 *  - "RSPLINE" requires integer-ordered problems (granularity 1 inputs); the synthetic
 *    ladder and the DEDS inventory problems all qualify.
 *  - "RestartSHC" wraps hill climbing in sequential random restarts; the outer solver
 *    aggregates the inner solver's replication counters after each restart, so the
 *    budget criterion sees the true consumption but can overshoot by up to one restart
 *    (an inner run ends on SHC's own no-improvement rule).
 *  - A `SolverPortfolio` can also be a case (it is a Solver built by a factory), but
 *    provisioning its per-member resources is study-specific, so it is not in the
 *    standard list.
 */
fun standardSolverCases(): List<SolverCase> {
    return listOf(
        stochasticHillClimberCase(),
        simulatedAnnealingCase(),
        crossEntropyCase(),
        rSplineCase(),
        randomRestartHillClimberCase()
    )
}

/** Stochastic hill climbing at library defaults. */
fun stochasticHillClimberCase(): SolverCase {
    return SolverCase(
        label = "SHC",
        solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
            StochasticHillClimber(pd, evaluator, name = name)
        },
        description = "Stochastic hill climbing, library defaults"
    )
}

/** Simulated annealing at library defaults (default temperature configuration and
 *  exponential cooling). */
fun simulatedAnnealingCase(): SolverCase {
    return SolverCase(
        label = "SA",
        solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
            SimulatedAnnealing(pd, evaluator, name = name)
        },
        description = "Simulated annealing, library defaults"
    )
}

/** Cross-entropy at library defaults (default normal sampler, sample size, elite
 *  percentage). */
fun crossEntropyCase(): SolverCase {
    return SolverCase(
        label = "CE",
        solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
            CrossEntropySolver(pd, evaluator, name = name)
        },
        description = "Cross-entropy, library defaults"
    )
}

/** R-SPLINE at library defaults (default initial sample size with the default growth
 *  schedule). Requires an integer-ordered problem. */
fun rSplineCase(): SolverCase {
    return SolverCase(
        label = "RSPLINE",
        solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
            RSplineSolver(
                pd, evaluator,
                replicationsPerEvaluation = FixedGrowthRateReplicationSchedule(
                    initialNumReps = RSplineSolver.defaultInitialSampleSize
                ),
                name = name
            )
        },
        description = "R-SPLINE, library defaults (integer-ordered problems only)"
    )
}

/** Sequential random restarts around a library-default hill climber. */
fun randomRestartHillClimberCase(): SolverCase {
    return SolverCase(
        label = "RestartSHC",
        solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
            val innerSolver = StochasticHillClimber(pd, evaluator, name = "${name}_inner")
            RandomRestartSolver(restartingSolver = innerSolver, name = name)
        },
        description = "Random restarts around stochastic hill climbing, library defaults"
    )
}
