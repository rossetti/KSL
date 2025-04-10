package ksl.simopt.solvers

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.IterativeProcess

open class SolverRunner(
    maximumIterations: Int,
    private val evaluator: Evaluator
) {
    init {
        require(maximumIterations > 0) { "maximum number of iterations must be > 0" }
    }

    private val mySolvers = mutableSetOf<Solver>()

    constructor(
        maximumIterations: Int,
        evaluator: Evaluator,
        solvers: Collection<Solver>
    ) : this(maximumIterations, evaluator) {
        for (solver in solvers) {
            addSolver(solver)
        }
    }

    private val mySolverIterativeProcess = SolverRunnerIterativeProcess()
    private val myEvaluationInterceptor = EvaluationInterceptor()

    var maximumIterations = maximumIterations
        set(value) {
            require(value > 0) { "maximum number of iterations must be > 0" }
            field = value
        }

    var iterationCounter = 0
        private set

    val problemDefinition: ProblemDefinition
        get() = evaluator.problemDefinition

    fun addSolver(solver: Solver) {
        mySolvers.add(solver)
        solver.myEvaluator = myEvaluationInterceptor
    }

    fun removeSolver(solver: Solver) : Boolean {
        solver.myEvaluator = solver.myOriginalEvaluator
        return mySolvers.remove(solver)
    }

    fun initialize() {
        mySolverIterativeProcess.initialize()
    }

    fun hasNextIteration(): Boolean {
        return mySolverIterativeProcess.hasNextStep()
    }

    fun runNextIteration(){
        mySolverIterativeProcess.runNext()
    }

    fun runAllIterations(){
        mySolverIterativeProcess.run()
    }

    fun stopIterations(){
        mySolverIterativeProcess.stop()
    }

    fun endIterations(){
        mySolverIterativeProcess.end()
    }

    private fun initializeIterations(){
        TODO("Not yet implemented")
    }

    private fun runIteration(){
        TODO("Not yet implemented")
    }

    private fun afterIterations(){
        TODO("Not yet implemented")
    }

    private fun hasMoreIterations(): Boolean{
        TODO("Not yet implemented")
    }

    /**
     *  The purpose of this function is to intercept any requests sent by solvers for evaluation
     *  so that the solver runner has an opportunity to process them before forwarding them
     *  to the attached evaluator.
     */
    protected open fun handleInterceptedRequestsFromSolvers(requests: List<EvaluationRequest>): List<Solution> {
        TODO("Not yet implemented")
    }

    private inner class SolverRunnerIterativeProcess : IterativeProcess<SolverRunnerIterativeProcess>("SolverRunnerIterativeProcess") {
        //TODO add some logging

        override fun initializeIterations() {
            super.initializeIterations()
            iterationCounter = 0
            this@SolverRunner.initializeIterations()
        }

        override fun hasNextStep(): Boolean {
            return hasMoreIterations()
        }

        override fun nextStep(): SolverRunnerIterativeProcess? {
            return if (!hasNextStep()) {
                null
            } else this
        }

        override fun runStep() {
            myCurrentStep = nextStep()
            runIteration()
            iterationCounter++
        }

        override fun endIterations() {
            afterIterations()
            super.endIterations()
        }

    }

    /**
     *  The purpose of this class is to intercept any requests sent by solvers for evaluation
     *  so that the solver runner has an opportunity to process them before forwarding them
     *  to the attached evaluator.
     */
    private inner class EvaluationInterceptor(): EvaluatorIfc by evaluator {
        override fun evaluate(requests: List<EvaluationRequest>): List<Solution> {
            return handleInterceptedRequestsFromSolvers(requests)
        }
    }
}