/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.simopt.tutorial

import ksl.examples.book.chapter7.RQInventorySystem
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.concurrent.MemberEvaluatorFactoryIfc
import ksl.simopt.solvers.concurrent.PooledMemberEvaluatorFactory
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.Interval
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/*
 * Tutorial Example 2 (Type 2: a discrete-event simulation model) -- shared setup.
 *
 * The model is the classic single-item (r, Q) inventory system. We optimize the
 * reorder point and the reorder quantity to MINIMIZE the expected ordering-and-
 * holding cost, SUBJECT TO an expected fill rate of at least 0.95.
 *
 * The model class itself (RQInventorySystem and the elements it contains) already
 * exists in the KSL examples; the tutorial reuses it and shows how to (a) build a
 * fresh, configured copy for optimization and (b) describe the optimization
 * problem over it.
 */

/**
 *  The model identifier. For a Type 2 problem this MUST equal the name of the
 *  Model that the builder creates, because the framework routes evaluation
 *  requests by this identifier.
 */
const val RQ_TUTORIAL_ID: String = "RQInventoryTutorial"

// The model's input keys and response names. An input key is written as
// "elementName.propertyName"; a response name is the response element's own name.
// Naming the (r, Q) system element "Inventory" makes its inner item element
// "Inventory:Item", so every key and response below carries that prefix.
const val RQ_OBJECTIVE: String = "Inventory:Item:OrderingAndHoldingCost"
const val RQ_FILL_RATE: String = "Inventory:Item:FillRate"
const val RQ_REORDER_QTY: String = "Inventory:Item.initialReorderQty"
const val RQ_REORDER_POINT: String = "Inventory:Item.initialReorderPoint"

/**
 *  Builds a fresh, fully configured (r, Q) model. A model builder MUST return a
 *  brand-new, independent Model on every call -- the framework may build many
 *  copies (for example, one per worker) and a shared instance would corrupt
 *  results.
 */
object BuildRQTutorialModel : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // The Model's name must match RQ_TUTORIAL_ID (the problem's identifier).
        val model = Model(RQ_TUTORIAL_ID)
        // Naming the system element "Inventory" fixes the input keys and response
        // names used by the problem definition below.
        val rqModel = RQInventorySystem(model, reorderPt = 1, reorderQty = 2, name = "Inventory")
        rqModel.initialOnHand = 0
        rqModel.demandGenerator.initialTimeBtwEvents = ExponentialRV(1.0 / 3.6)
        rqModel.leadTime.initialRandomSource = ConstantRV(0.5)
        // A long run with a warm-up period gives a good long-run cost estimate.
        model.lengthOfReplication = 20000.0
        model.lengthOfReplicationWarmUp = 10000.0
        model.numberOfReplications = 40
        return model
    }
}

/**
 *  Describes the optimization problem over the (r, Q) model: minimize the expected
 *  ordering-and-holding cost over the two integer decision variables, subject to
 *  an expected fill rate of at least 0.95. The input names must be control keys of
 *  the model and the response names must be responses the model produces.
 */
fun makeRQProblem(): ProblemDefinition {
    val problem = ProblemDefinition(
        problemName = "RQInventoryConstrained",
        modelIdentifier = RQ_TUTORIAL_ID,
        objFnResponseName = RQ_OBJECTIVE,
        inputNames = listOf(RQ_REORDER_QTY, RQ_REORDER_POINT),
        responseNames = listOf(RQ_FILL_RATE)
    )
    problem.inputVariable(name = RQ_REORDER_QTY, interval = Interval(1.0, 100.0), granularity = 1.0)
    problem.inputVariable(name = RQ_REORDER_POINT, interval = Interval(1.0, 100.0), granularity = 1.0)
    // The probabilistic (response) constraint: E[fill rate] >= 0.95.
    problem.responseConstraint(
        name = RQ_FILL_RATE,
        rhsValue = 0.95,
        inequalityType = InequalityType.GREATER_THAN
    )
    return problem
}

/**
 *  The evaluator factory the benchmark uses. For a Type 2 problem the models are
 *  pooled and reused across runs (models are expensive to build), which is safe
 *  because every run positions its random-number streams absolutely.
 */
fun makeRQEvaluatorFactory(problem: ProblemDefinition): MemberEvaluatorFactoryIfc {
    return PooledMemberEvaluatorFactory(problem, BuildRQTutorialModel)
}

/**
 *  Packages the (r, Q) problem as a benchmark-ready case. There is no closed-form
 *  optimum, so we supply no reference solution; the benchmark then measures each
 *  run's gap against the best objective found across the experiment.
 */
fun makeRQProblemCase(): ProblemCase {
    return ProblemCase(
        name = "RQInventoryConstrained",
        problemDefinitionFactory = ::makeRQProblem,
        evaluatorFactoryProvider = ::makeRQEvaluatorFactory,
        tags = mapOf("family" to "inventoryDEDS", "dimension" to "2", "constrained" to "true")
    )
}
