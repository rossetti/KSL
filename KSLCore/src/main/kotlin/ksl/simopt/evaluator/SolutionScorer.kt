package ksl.simopt.evaluator

/**
 *  Scores solutions at ONE evaluation clock.
 *
 *  The penalized objective is not a property of a solution. It is a property of a solution *and a
 *  clock*: with a dynamic penalty, `f + M0 * k * v` at `k = 1` and at `k = 500` differ by a factor
 *  of five hundred on the violation term. The penalty method is a sequence of subproblems — at
 *  iteration `k` the solver minimizes `f + M_k * v` — so a penalized value carried over from
 *  iteration `j` is a value of a *different objective function*, and mixing the two compares
 *  solutions to two different problems.
 *
 *  A scorer makes the clock explicit and shared. Any decision taken over more than one solution
 *  should be taken through a single scorer, so that the whole decision sits inside one subproblem
 *  of the sequence. Collections that outlive an iteration — a population rebuilt from elites plus
 *  offspring, a surrogate's archive, a stored incumbent — are the ones that need this, because
 *  they are the ones that hold solutions stamped at different clocks.
 *
 *  This is the *search* key. For choosing what to REPORT, prefer [FeasibilityFirstComparator],
 *  which is clock-independent by construction.
 *
 *  @param evaluationNumber the clock every solution is judged at
 */
class SolutionScorer(val evaluationNumber: Int) {

    init {
        require(evaluationNumber >= 0) { "The evaluation number must be >= 0" }
    }

    /** The solution restamped to this scorer's clock. */
    fun at(solution: Solution): Solution = solution.atEvaluation(evaluationNumber)

    /** The penalized objective of [solution] judged at this scorer's clock. */
    fun score(solution: Solution): Double = solution.penalizedObjFncValueAt(evaluationNumber)

    /** The penalized objectives of [solutions], all judged at this scorer's clock. */
    fun scores(solutions: List<Solution>): DoubleArray =
        DoubleArray(solutions.size) { score(solutions[it]) }

    /**
     *  Orders by score at this scorer's clock. A genuine total order, and therefore safe to sort
     *  with — unlike a rule that judges each pair at its own clock.
     */
    val comparator: Comparator<Solution> = Comparator { a, b -> score(a).compareTo(score(b)) }

    override fun toString(): String = "SolutionScorer(evaluationNumber=$evaluationNumber)"
}
