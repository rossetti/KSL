package ksl.simopt.benchmark

import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.concurrent.MemberEvaluatorFactoryIfc

/**
 *  A named, self-contained problem for benchmarking: fresh problem definitions on
 *  demand, fresh per-member evaluation resources on demand, an optional reference
 *  solution, and descriptive tags for analysis grouping.
 *
 *  Provisioning a `MemberEvaluatorFactoryIfc` is what unifies discrete-event and
 *  synthetic problems: a DEDS problem supplies a pooled model-backed factory (see
 *  `ksl.simopt.solvers.concurrent.PooledMemberEvaluatorFactory`), a synthetic or static
 *  Monte Carlo problem supplies a [FunctionMemberEvaluatorFactory] — the harness treats
 *  them identically.
 *
 *  Both factories must return fresh, independent instances on every call: a benchmark
 *  experiment invokes them once per problem run so that no state leaks between
 *  experiments, and the returned member-evaluator factory is invoked concurrently on
 *  worker threads.
 *
 *  @param name a unique (within an experiment), stable name for the problem; flows into
 *  cell labels and result records
 *  @param problemDefinitionFactory creates a fresh problem definition on each call
 *  @param evaluatorFactoryProvider creates the per-member evaluation-resource factory
 *  for one run of the problem. The supplied problem definition is the instance the
 *  harness created for the run and will hand to every cell's solver — the member
 *  evaluators must be built against that same instance (solutions validate their
 *  problem identity), which is why it is an argument rather than something the provider
 *  creates for itself
 *  @param referenceSolution the reference the problem's runs are gapped against; null
 *  when no reference exists (runs are then gapped against the best found in the
 *  experiment)
 *  @param tags descriptive key-value pairs for analysis grouping (e.g. dimension,
 *  family, noiseLevel, constrained)
 */
class ProblemCase(
    val name: String,
    val problemDefinitionFactory: () -> ProblemDefinition,
    val evaluatorFactoryProvider: (problemDefinition: ProblemDefinition) -> MemberEvaluatorFactoryIfc,
    val referenceSolution: ReferenceSolution? = null,
    val tags: Map<String, String> = emptyMap()
) {
    init {
        require(name.isNotBlank()) { "The problem case name must not be blank" }
    }

    override fun toString(): String {
        return "ProblemCase(name=$name, reference=${referenceSolution?.type ?: "none"}, tags=$tags)"
    }
}
