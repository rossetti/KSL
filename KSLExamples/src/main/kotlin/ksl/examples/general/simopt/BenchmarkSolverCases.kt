package ksl.examples.general.simopt

import ksl.simopt.benchmark.BenchmarkSolverFactoryIfc
import ksl.simopt.benchmark.SolverCase
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule
import ksl.simopt.solvers.algorithms.CrossEntropySolver
import ksl.simopt.solvers.algorithms.RSplineSolver
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simopt.solvers.algorithms.SimulatedAnnealing
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simopt.solvers.algorithms.bo.BayesianOptimizationSolver
import ksl.simopt.solvers.algorithms.genetic.GeneticAlgorithmSolver
import ksl.simopt.solvers.algorithms.isc.ISCSolver
import ksl.simopt.solvers.algorithms.pso.ParticleSwarmSolver

/**
 *  The standard solver-case registry for benchmark studies: the paper's "vanilla"
 *  configurations, one named case per algorithm family, each at its LIBRARY DEFAULTS
 *  (the solver constructors' own default parameter values). This file replaces the old
 *  SolverType enums and comment-toggled solverFactory functions — a study picks cases
 *  from this list (or adds variants, which are one-line additions) instead of editing
 *  a when-expression.
 *
 *  Three roster helpers: [standardSolverCases] (the five published/baseline algorithms),
 *  [newSolverCases] (the four newer subjects GA, PSO, BO, ISC), and [allSolverCases]
 *  (all nine, the Study-1 roster).
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
 *  - "GA" and "PSO" are population-based and IGNORE the common starting point (they seed
 *    their population/swarm themselves); this is inherent, not a configuration choice.
 *  - "PSO" evaluates its whole swarm as a single batch oracle call. The parallelism of
 *    that batch is a property of the EVALUATOR, not of PSO: under the benchmark harness
 *    each cell's member evaluator is sequential (a single-threaded oracle/provider), so
 *    the swarm is evaluated sequentially within the cell — no thread-safety issue, and
 *    no special pinning is needed.
 *  - "BO" is sample-efficient: its cost is dominated by per-iteration Gaussian-process
 *    fits, not by replications, so equal-REPLICATION budgets are fair but BO's wall time
 *    may be disproportionate (measured in the study's smoke phase).
 *  - "ISC" (industrial-strength COMPASS) takes its indifference zones from the problem:
 *    deltaC defaults to `ProblemDefinition.indifferenceZoneParameter` and deltaL to
 *    deltaC. Setting a meaningful indifference zone on each problem (as Study 1 does)
 *    gives ISC its intended correct-selection guarantees; leaving it 0 puts ISC in its
 *    documented degraded mode.
 *  - A `SolverPortfolio` can also be a case (it is a Solver built by a factory), but
 *    provisioning its per-member resources is study-specific, so it is not in any list.
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

/** The four newer solver subjects (GA, PSO, BO, ISC) at library defaults. */
fun newSolverCases(): List<SolverCase> {
    return listOf(
        geneticAlgorithmCase(),
        particleSwarmCase(),
        bayesianOptimizationCase(),
        iscCase()
    )
}

/** The full Study-1 roster: the five baselines plus the four newer subjects (nine cases). */
fun allSolverCases(): List<SolverCase> {
    return standardSolverCases() + newSolverCases()
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

/** Genetic algorithm at library defaults (tournament selection, blend crossover,
 *  Gaussian mutation, elitism, default population size). Ignores the starting point. */
fun geneticAlgorithmCase(): SolverCase {
    return SolverCase(
        label = "GA",
        solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
            GeneticAlgorithmSolver(pd, evaluator, name = name)
        },
        description = "Genetic algorithm, library defaults (population-based; ignores starting point)"
    )
}

/** Particle swarm at library defaults (default swarm size, linear-decreasing inertia,
 *  default cognitive/social coefficients, clamp-to-bounds). Ignores the starting point;
 *  swarm evaluated as one batch through the cell's sequential evaluator. */
fun particleSwarmCase(): SolverCase {
    return SolverCase(
        label = "PSO",
        solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
            ParticleSwarmSolver(pd, evaluator, name = name)
        },
        description = "Particle swarm, library defaults (population-based; ignores starting point)"
    )
}

/** Bayesian optimization at library defaults (Gaussian-process surrogate, expected
 *  improvement, Latin-hypercube initial design). Sample-efficient; wall time is
 *  GP-fit-bound rather than replication-bound. */
fun bayesianOptimizationCase(): SolverCase {
    return SolverCase(
        label = "BO",
        solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
            BayesianOptimizationSolver(pd, evaluator, name = name)
        },
        description = "Bayesian optimization, library defaults (GP surrogate; GP-fit-bound wall time)"
    )
}

/** Industrial-strength COMPASS at library defaults (Niching-GA global phase, COMPASS
 *  local phases, clean-up selection). Its indifference zones (deltaC, deltaL) are taken
 *  from the problem's indifferenceZoneParameter — set a meaningful value on each problem
 *  for ISC's correct-selection guarantees.
 *
 *  ISC's macro-step is a whole phase that runs to its own termination, and the benchmark
 *  budget criterion is only checked between macro-steps, so a single global phase can
 *  consume orders of magnitude more replications than the benchmark budget. Supply a
 *  [globalBudget] (typically the benchmark's replication budget) to bound ISC's global
 *  phase so its consumption stays comparable to the other solvers; null (the default)
 *  leaves ISC unbounded at library defaults.
 *
 *  @param globalBudget optional replication budget for ISC's global phase; null for the
 *  library default (unbounded global phase)
 */
@JvmOverloads
fun iscCase(globalBudget: Int? = null): SolverCase {
    return SolverCase(
        label = "ISC",
        solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
            ISCSolver(pd, evaluator, globalBudget = globalBudget, name = name)
        },
        description = "Industrial-strength COMPASS, IZ from the problem definition" +
                (globalBudget?.let { ", global phase budget $it" } ?: ", library defaults")
    )
}
