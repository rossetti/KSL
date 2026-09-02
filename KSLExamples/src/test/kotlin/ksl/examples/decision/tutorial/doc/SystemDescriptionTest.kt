package ksl.examples.decision.tutorial.doc

import ksl.examples.decision.ClinicSubsystem
import ksl.examples.decision.ShipmentDepot
import ksl.examples.decision.tutorial.StockRoom
import ksl.modeling.decision.DecisionElement
import ksl.modeling.decision.descriptor.SumEquals
import ksl.modeling.station.StationNetwork
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  **The tutorial's system descriptions must describe the systems.**
 *
 *  Each part opens with a table of parameters so a reader can build the model before reading
 *  any decision code. A table like that is worse than no table if it drifts: a student who
 *  builds from it gets a model that does not match the one the numbers in the text came
 *  from, and nothing tells either of them.
 *
 *  Prose cannot be checked, but **numbers can**. This test parses the parameter tables out
 *  of the tutorial and compares every value that is visible in a built model's decision
 *  surface — review periods, lever ranges, reward rates, the staffing budget, the truck
 *  capacity — against the model itself. Distributional parameters that are private
 *  constructor defaults are checked where they are reachable and are otherwise listed here
 *  as unverified, rather than being silently trusted.
 */
class SystemDescriptionTest {

    private fun repoFile(relative: String): File =
        listOf(relative, "../$relative")
            .map(::File).firstOrNull { it.isFile }
            ?: fail("cannot locate $relative from ${File(".").absolutePath}")

    private val tutorial: String by lazy { repoFile("docs/guides/ksl-decision-tutorial.md").readText() }

    /** The text of the parameter table row whose first cell is [label], within [section]. */
    private fun row(section: String, label: String): String {
        val body = tutorial.substringAfter("### $section").substringBefore("\n### ")
        val line = body.lines().firstOrNull { it.trimStart().startsWith("| $label |") }
            ?: fail("the tutorial's '$section' has no parameter row labelled '$label'")
        return line.split("|")[2].trim()
    }

    /** The first number appearing in that row's value cell. */
    private fun number(section: String, label: String): Double {
        val cell = row(section, label)
        val m = Regex("""-?\d+(?:,\d{3})*(?:\.\d+)?""").find(cell)
            ?: fail("no number in the '$label' row of '$section': $cell")
        return m.value.replace(",", "").toDouble()
    }

    /** Every number appearing in that row's value cell, in order. */
    private fun numbers(section: String, label: String): List<Double> =
        Regex("""-?\d+(?:,\d{3})*(?:\.\d+)?""").findAll(row(section, label))
            .map { it.value.replace(",", "").toDouble() }.toList()

    private fun rate(element: DecisionElement, alias: String): Double =
        element.descriptor().rewards.first { it.name == alias }.rate

    // ---- Part II / V / VI: the stock room ---------------------------------------

    @Test
    fun thePartTwoTableDescribesTheStockRoom() {
        val model = Model("Described")
        val room = StockRoom(model, name = "Room")
        val surface = room.review.descriptor()

        println()
        println("review interval : table ${number("2.1 The system", "Review period")}  model ${room.reviewPeriod}")
        println("order range     : table ${numbers("2.1 The system", "Order quantity range")}  " +
            "model ${surface.levers[0].lowerBound}..${surface.levers[0].upperBound}")
        println("holding rate    : table ${number("2.1 The system", "Holding cost")}  model ${rate(room.review, "Holding")}")
        println("shortage rate   : table ${number("2.1 The system", "Shortage cost")}  model ${rate(room.review, "Shortage")}")

        assertEquals(room.reviewPeriod, number("2.1 The system", "Review period"),
            "the tutorial states a review period the model does not use")
        val range = numbers("2.1 The system", "Order quantity range")
        assertEquals(listOf(surface.levers[0].lowerBound, surface.levers[0].upperBound), range,
            "the tutorial states an order quantity range the lever does not declare")
        assertEquals(rate(room.review, "Holding"), number("2.1 The system", "Holding cost"))
        assertEquals(rate(room.review, "Shortage"), number("2.1 The system", "Shortage cost"))

        // The cost ratio is the economics of the whole example, and the text claims ten to one.
        assertEquals(10.0, rate(room.review, "Shortage") / rate(room.review, "Holding"),
            "the tutorial calls this a ten-to-one ratio and builds its intuition on that")
    }

    /** The initial on-hand level the table states is the level the model starts a run at. */
    @Test
    fun theStockRoomStartsWhereTheTableSaysItDoes() {
        val model = Model("Described")
        val room = StockRoom(model, name = "Room")
        model.numberOfReplications = 1
        model.lengthOfReplication = 1.0
        assertEquals(number("2.1 The system", "Initial on hand"), room.onHand.initialValue,
            "the tutorial states an initial inventory the model does not start from")
    }

    // ---- Part III: the clinic ---------------------------------------------------

    @Test
    fun thePartThreeTableDescribesTheClinic() {
        val model = Model("DescribedClinic")
        val flow = StationNetwork(model, "ClinicFlow")
        val clinic = ClinicSubsystem(model, exit = flow.sink("Exit"), name = "Clinic")
        flow.source("Patients", ExponentialRV(5.0, streamNum = 3), firstReceiver = clinic.entry)
        val surface = clinic.shiftReview.descriptor()

        val budget = surface.constraints.filterIsInstance<SumEquals>().single().total
        println()
        println("shift review : table ${number("3.1 The system", "Shift review")}  model ${clinic.reviewPeriod}")
        println("staff pool   : table ${number("3.1 The system", "Staff pool")}  model $budget")
        println("revenue      : table ${number("3.1 The system", "Revenue")}  model ${rate(clinic.shiftReview, "Revenue")}")
        println("waiting      : table ${number("3.1 The system", "Waiting charge")}  " +
            "model ${rate(clinic.shiftReview, "TriageWait")}/${rate(clinic.shiftReview, "ExamWait")}")

        assertEquals(clinic.reviewPeriod, number("3.1 The system", "Shift review"),
            "the tutorial states a shift length the model does not review on")
        assertEquals(budget, number("3.1 The system", "Staff pool"),
            "the tutorial states a staff pool the budget constraint does not declare")
        assertEquals(rate(clinic.shiftReview, "Revenue"), number("3.1 The system", "Revenue"))
        assertEquals(rate(clinic.shiftReview, "TriageWait"), number("3.1 The system", "Waiting charge"))
        assertEquals(rate(clinic.shiftReview, "ExamWait"), number("3.1 The system", "Waiting charge"),
            "the table gives ONE waiting charge for both queues, so the two rates must agree")

        val perStage = numbers("3.1 The system", "Per-stage staffing range")
        assertEquals(listOf(surface.levers[0].lowerBound, surface.levers[0].upperBound), perStage.take(2),
            "the tutorial states a per-stage range the levers do not declare")
    }

    /**
     *  The offered-load arithmetic the part turns on: (1/5)x6 = 1.2 at triage, (1/5)x12 = 2.4 at
     *  exam. An earlier draft said 2.0 at triage, which made the 1:2 demand split — the reason
     *  3/5 is the right answer — read as though it were 1:1.2.
     */
    @Test
    fun theOfferedLoadArithmeticInPartThreeIsRight() {
        val body = tutorial.substringAfter("### 3.1 The system").substringBefore("\n### ")
        val arrivalMean = number("3.1 The system", "Time between patient arrivals")
        val triageMean = number("3.1 The system", "Triage service time")
        val examMean = number("3.1 The system", "Exam service time")

        val triageLoad = triageMean / arrivalMean
        val examLoad = examMean / arrivalMean
        println()
        println("offered load: triage=%.1f exam=%.1f total=%.1f".format(
            triageLoad, examLoad, triageLoad + examLoad))

        assertEquals(1.2, triageLoad, 1e-9)
        assertEquals(2.4, examLoad, 1e-9)
        assertTrue(body.contains("1.2 server-units"),
            "the part's argument is that exam is offered TWICE the work of triage; the text must " +
                "state triage's offered load as 1.2, not as anything else")
        assertTrue(body.contains("2.4 server-units"))
        assertTrue(body.contains("3.6 server-units"),
            "the total offered load against eight staff is what makes the clinic comfortably " +
                "staffed in total and badly split, which is the whole setup")
    }

    // ---- Part IV: the depot ------------------------------------------------------

    @Test
    fun thePartFourTableDescribesTheDepot() {
        val model = Model("DescribedDepot")
        val depot = ShipmentDepot(model, name = "Depot")
        val surface = depot.allocation.descriptor()

        println()
        println("review    : table ${number("4.1 The system", "Review period")}  model ${depot.reviewPeriod}")
        println("truck     : table ${number("4.1 The system", "Truck capacity")}  " +
            "model ${surface.levers[0].upperBound}")
        println("shortage  : table ${numbers("4.1 The system", "Shortage cost")}  " +
            "model ${depot.shortageRates.toList()}")

        assertEquals(depot.reviewPeriod, number("4.1 The system", "Review period"),
            "the tutorial states a review period the depot does not use")
        assertEquals(surface.levers[0].upperBound, number("4.1 The system", "Truck capacity"),
            "the truck capacity is the lever ENVELOPE, so the table and the declaration must agree")
        assertEquals(depot.shortageRates.toList(), numbers("4.1 The system", "Shortage cost"),
            "the 9/3/1 asymmetry is why a greedy rule beats a proportional one; if the table and " +
                "the model disagree the part's conclusion is about a different system")
        assertEquals(3, surface.levers.size, "one lever per region")
    }

    /**
     *  The part's two stated design choices are arithmetic, so they are checked as arithmetic:
     *  supply barely exceeds demand, and the truck is slack relative to the shelf.
     */
    @Test
    fun theDepotsStatedDesignChoicesHold() {
        val demandPerRegion = number("4.1 The system", "Demand per region")
        val totalDemand = number("4.1 The system", "Total demand")
        val resupply = numbers("4.1 The system", "Replenishment")

        val supplyRate = resupply[0] / resupply[1]
        println()
        println("demand %.2f/unit time, supply %.3f/unit time, margin %.1f%%".format(
            totalDemand, supplyRate, 100.0 * (supplyRate - totalDemand) / totalDemand))

        assertEquals(3.0 * demandPerRegion, totalDemand, 1e-9,
            "three regions at the stated per-region rate must give the stated total")
        assertTrue(supplyRate > totalDemand,
            "the depot must be stable, or every rule loses to backlog growth: $supplyRate vs $totalDemand")
        assertTrue((supplyRate - totalDemand) / totalDemand < 0.05,
            "the part claims supply BARELY exceeds demand, which is what keeps stock scarce " +
                "enough for the allocation to matter: the margin is " +
                "${100.0 * (supplyRate - totalDemand) / totalDemand}%")
    }
}
