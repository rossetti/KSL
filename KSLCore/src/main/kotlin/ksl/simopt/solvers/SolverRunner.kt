package ksl.simopt.solvers

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.RequestData
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.IterativeProcess

open class SolverRunner(
    private val evaluator: Evaluator
) {

    /**
     *  Contains the solvers that are managed.
     */
    private val mySolvers = mutableSetOf<Solver>()

    /**
     *  As solvers run, they may complete their iterations.
     *  This set holds those solvers that need to continue running iterations.
     */
    private val myRunnableSolvers = mutableListOf<Solver>()

    constructor(
        evaluator: Evaluator,
        solvers: Collection<Solver>
    ) : this(evaluator) {
        for (solver in solvers) {
            addSolver(solver)
        }
    }

    private val mySolverIterativeProcess = SolverRunnerIterativeProcess()

    /**
     *  Represents the maximum number of iterations that the solver will
     *  perform. This is determined as the maximum of the maximum number
     *  of iterations across all managed solvers.
     */
    var maximumIterations = 0
        private set(value) {
            require(value > 0) { "maximum number of iterations must be > 0" }
            field = value
        }

    var iterationCounter = 0
        private set

    val problemDefinition: ProblemDefinition
        get() = evaluator.problemDefinition

    fun addSolver(solver: Solver) {
        mySolvers.add(solver)
        solver.mySolverRunner = this
    }

    fun removeSolver(solver: Solver): Boolean {
        solver.mySolverRunner = null
        return mySolvers.remove(solver)
    }

    fun initialize() {
        mySolverIterativeProcess.initialize()
    }

    fun hasNextIteration(): Boolean {
        return mySolverIterativeProcess.hasNextStep()
    }

    fun runNextIteration() {
        mySolverIterativeProcess.runNext()
    }

    fun runAllIterations() {
        mySolverIterativeProcess.run()
    }

    fun stopIterations() {
        mySolverIterativeProcess.stop()
    }

    fun endIterations() {
        mySolverIterativeProcess.end()
    }

    /**
     *  This function should cause any managed solvers
     *  to be initialized.
     */
    private fun initializeIterations() {
        resetEvaluator()
        maximumIterations = mySolvers.maxOf { it.maximumNumberIterations }
        // setup to run all the solvers
        myRunnableSolvers.clear()
        myRunnableSolvers.addAll(mySolvers)
        for (solver in myRunnableSolvers) {
            solver.initialize()
        }
        //TODO what else?
    }

    /**
     *  This function should cause any managed solvers
     *  to run their individual iterations of their algorithms.
     */
    private fun runIteration() {
        for (solver in myRunnableSolvers) {
            solver.runNextIteration()
        }
        // check if solver is done if so remove from runnable solvers
        // cause completed solvers to end their iterations
        for(solver in mySolvers){
            //TODO this needs to also check if the solver is DONE/STOPPED
            if (!solver.hasNextIteration()){
                myRunnableSolvers.remove(solver)
                solver.endIterations()
            }
        }
        //TODO what else?
    }

    /**
     *  This function should cause any managed solvers
     *  to clean-up after running their iterations.
     */
    private fun afterIterations() {
        for (solver in myRunnableSolvers) {
            solver.endIterations()
        }
        //TODO what else?
    }

    /**
     *  This function should determine if any managed solvers
     *  have more iterations to run.
     */
    private fun hasMoreIterations(): Boolean {
        return !(myRunnableSolvers.isEmpty() || iterationCounter >= maximumIterations)
    }

    /**
     *  The purpose of this function is to intercept any requests sent by solvers for evaluation
     *  so that the solver runner has an opportunity to process them before forwarding them
     *  to the attached evaluator.
     */
    internal fun receiveEvaluationRequests(solver: Solver, requests: List<RequestData>) : List<Solution> {
        // requests may come from solvers because of initialization or because of an iteration
        TODO("Not yet implemented")
    }

    internal fun resetEvaluator(){
        evaluator.resetEvaluationCounts()
    }

    private inner class SolverRunnerIterativeProcess :
        IterativeProcess<SolverRunnerIterativeProcess>("SolverRunnerIterativeProcess") {
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

}