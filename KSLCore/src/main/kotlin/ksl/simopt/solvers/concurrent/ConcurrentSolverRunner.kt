package ksl.simopt.solvers.concurrent

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simulation.SimulationDispatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * The engine for concurrent solver execution: runs the member solvers of a concurrent
 * run (parallel random restarts, a solver portfolio) on a bounded dispatcher, each with
 * its own factory-provisioned evaluation resources, and hands their results back in
 * deterministic member order.
 *
 * Execution model:
 *  - [launchAll] starts every member as a coroutine on a dispatcher bounded by the
 *    worker count; excess members queue and run as workers free up. Non-blocking.
 *  - [awaitMember] blocks until the given member completes and returns its result.
 *    Owners that report per-member progress await members in submission order, which
 *    makes tracker output and best-solution evolution deterministic regardless of which
 *    member happens to finish first.
 *  - [requestStop] forwards a graceful stop to every in-flight member solver (each
 *    checks its stop flag between its own iterations) and prevents queued members from
 *    starting. Safe to call from any thread; idempotent.
 *
 * Determinism contract: every member's inputs (starting point, stream block, solver
 * streams) are fixed at launch time and independent of scheduling, and results are
 * consumed in member order — so for a fixed seed setup the run's results do not depend
 * on the worker count or thread timing.
 *
 * Failure discipline mirrors the parallel evaluation provider: a member that throws
 * yields a failed [SolverMemberResult] carrying the problem's bad solution; sibling
 * members are unaffected. Cooperative cancellation exceptions propagate.
 *
 * Thread-safety expectations on shared problem objects: members share the (read-only)
 * problem definition. Its configuration must not be mutated while a run is in flight,
 * and any custom penalty functions attached to the problem's constraints must be
 * stateless (pure functions of their arguments, like the library-provided ones) —
 * members evaluate penalized objective values concurrently, so a penalty function that
 * keeps internal mutable state would race.
 *
 * @param problemDefinition the problem all members solve; supplies the bad solution for
 * failed or never-started members
 * @param tasks the members to run, in order; labels must be unique
 * @param evaluatorFactory provisions each member's private evaluation resources
 * @param numWorkers the maximum number of members running at the same time; null uses
 * the smaller of the member count and the available processors
 */
class ConcurrentSolverRunner(
    private val problemDefinition: ProblemDefinition,
    private val tasks: List<SolverMemberTask>,
    private val evaluatorFactory: MemberEvaluatorFactoryIfc,
    numWorkers: Int? = null
) {

    init {
        require(tasks.isNotEmpty()) { "At least one member task must be supplied" }
        require(numWorkers == null || numWorkers > 0) { "numWorkers must be > 0 when specified" }
        val labels = tasks.map { it.label }
        require(labels.toSet().size == labels.size) { "Member labels must be unique; got $labels" }
    }

    /** The number of members that may run at the same time. */
    val numWorkers: Int = numWorkers ?: min(tasks.size, SimulationDispatcher.availableProcessors)

    /** The number of members in the run. */
    @Suppress("unused")
    val numMembers: Int
        get() = tasks.size

    private val myDispatcher = Dispatchers.IO.limitedParallelism(this.numWorkers)
    private val myScope = CoroutineScope(SupervisorJob() + myDispatcher)
    private val myLiveSolvers = ConcurrentHashMap<Int, Solver>()
    private val myStopRequested = AtomicBoolean(false)

    @Volatile
    private var myStopMessage: String? = null

    private var myDeferredResults: List<Deferred<SolverMemberResult>>? = null

    /** True once launchAll() has been called. */
    val isLaunched: Boolean
        get() = myDeferredResults != null

    /** True once a stop has been requested via requestStop(). */
    @Suppress("unused")
    val isStopRequested: Boolean
        get() = myStopRequested.get()

    /**
     * Launches every member as a coroutine on the bounded dispatcher and returns
     * immediately. May only be called once.
     */
    fun launchAll() {
        check(myDeferredResults == null) { "launchAll() may only be called once" }
        logger.debug { "ConcurrentSolverRunner: launching ${tasks.size} members on $numWorkers workers" }
        myDeferredResults = tasks.mapIndexed { index, task ->
            myScope.async { runMember(index, task) }
        }
    }

    /**
     * Blocks until the member with the given index completes and returns its result.
     * The await establishes the happens-before ordering that makes the member's solver
     * state safe to read from the calling thread.
     *
     * @param memberIndex the 0-based member index
     */
    fun awaitMember(memberIndex: Int): SolverMemberResult {
        val deferred = checkNotNull(myDeferredResults) { "launchAll() must be called before awaiting" }
        require(memberIndex in deferred.indices) { "Member index $memberIndex out of range" }
        return runBlocking { deferred[memberIndex].await() }
    }

    /**
     * Blocks until every member completes and returns all results in member order.
     */
    @Suppress("unused")
    fun awaitAllMembers(): List<SolverMemberResult> {
        val deferred = checkNotNull(myDeferredResults) { "launchAll() must be called before awaiting" }
        return runBlocking { deferred.awaitAll() }
    }

    /**
     * Requests a graceful stop of the whole run: members that have not started will not
     * start, and every in-flight member solver is signaled via its stopIterations
     * mechanism, so it exits after its current iteration. Idempotent; safe from any
     * thread.
     *
     * @param msg an optional message describing why the run is stopping
     */
    fun requestStop(msg: String? = null) {
        if (myStopRequested.compareAndSet(false, true)) {
            myStopMessage = msg
            logger.debug { "ConcurrentSolverRunner: stop requested ($msg)" }
        }
        for (solver in myLiveSolvers.values) {
            runCatching { solver.stopIterations(msg) }
        }
    }

    /**
     * Cancels the runner's coroutine scope. Call after all members have been awaited (or
     * after a stop) to release the scope; awaiting after shutdown is an error.
     */
    fun shutdown() {
        myScope.cancel()
    }

    private fun runMember(index: Int, task: SolverMemberTask): SolverMemberResult {
        if (myStopRequested.get()) {
            logger.debug { "Member $index (${task.label}) skipped: stop requested before start" }
            return SolverMemberResult(
                memberIndex = index,
                label = task.label,
                bestSolution = problemDefinition.badSolution(),
                numOracleCalls = 0,
                numReplicationsRequested = 0,
                solverResult = null,
                status = MemberStatus.STOPPED_BEFORE_START
            )
        }
        var evaluator: EvaluatorIfc? = null
        var reusable = false
        try {
            evaluator = evaluatorFactory.createEvaluator(index)
            val solver = task.solverFactory.create(evaluator, index, task.label)
            if (task.startingPoint != null) {
                solver.startingPoint = task.startingPoint
            }
            task.innerSolverDecorator?.invoke(solver, index)
            myLiveSolvers[index] = solver
            // Close the race between the stop-flag check above and registration: a stop
            // that arrived in between only saw an unregistered solver, so signal it here.
            if (myStopRequested.get()) {
                runCatching { solver.stopIterations(myStopMessage) }
            }
            logger.debug { "Member $index (${task.label}) starting" }
            solver.runAllIterations()
            reusable = true
            logger.debug { "Member $index (${task.label}) completed" }
            return SolverMemberResult(
                memberIndex = index,
                label = task.label,
                bestSolution = solver.bestSolution,
                numOracleCalls = solver.numOracleCalls,
                numReplicationsRequested = solver.numReplicationsRequested,
                solverResult = solver.solverResult,
                status = MemberStatus.COMPLETED
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Member $index (${task.label}) failed: ${e.message}" }
            return SolverMemberResult(
                memberIndex = index,
                label = task.label,
                bestSolution = problemDefinition.badSolution(),
                numOracleCalls = 0,
                numReplicationsRequested = 0,
                solverResult = null,
                status = MemberStatus.FAILED,
                error = e
            )
        } finally {
            myLiveSolvers.remove(index)
            evaluator?.let { evaluatorFactory.release(index, it, reusable) }
        }
    }

    companion object {
        val logger: KLogger = KotlinLogging.logger {}
    }
}
