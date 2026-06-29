package ksl.animation

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies the 8K.2 layout validator and the scaffold generator. */
class AnimationValidationTest {

    private fun buildModel(): Model {
        val m = Model("valModel")
        ResourceWithQ(m, "Worker")   // exposes "Worker" and "Worker:Q"
        HoldQueue(m, "WaitQ")
        Response(m, "SystemTime")
        Counter(m, "NumDone")
        return m
    }

    @Test
    fun `a correct layout validates clean (8K2)`() {
        val m = buildModel()
        val layout = m.animation {
            resourceWithQ("Worker", 100.0, 100.0)
            queue("WaitQ", 300.0, 100.0)
            value("SystemTime", 0.0, 0.0)
            bar("NumDone", 0.0, 0.0)
        }
        val report = layout.validateAgainst(m)
        assertTrue(report.isValid, report.toString())
    }

    @Test
    fun `unmatched bindings are flagged with a suggestion (8K2)`() {
        val m = buildModel()
        val layout = m.animation {
            resource("Workr", 0.0, 0.0)      // typo -> Worker
            queue("WaitQueue", 0.0, 0.0)     // not a queue name
            value("SystemTim", 0.0, 0.0)     // typo -> SystemTime
            station("Nowhere", 0.0, 0.0)     // NOT validated (free-form marker), so no issue expected
        }
        val report = layout.validateAgainst(m)
        assertFalse(report.isValid)
        val kinds = report.issues.map { it.kind }.toSet()
        assertTrue(ValidationIssue.Kind.UNMATCHED_RESOURCE in kinds)
        assertTrue(ValidationIssue.Kind.UNMATCHED_QUEUE in kinds)
        assertTrue(ValidationIssue.Kind.UNMATCHED_RESPONSE in kinds)
        // station() markers are free-form anchors (not model-bound), so they are not validated here.
        assertTrue(report.issues.none { it.name == "Nowhere" }, "station markers are not model-validated")
        assertTrue(
            report.issues.any { it.name == "Workr" && it.message.contains("Worker") },
            "expected a 'did you mean Worker?' hint: $report"
        )
    }

    @Test
    fun `scaffold is populated and self-consistent (8K2)`() {
        val m = buildModel()
        val layout = m.scaffoldLayout()
        assertTrue(layout.resources.any { it.resourceName == "Worker" })
        assertTrue(layout.queues.any { it.queueName == "Worker:Q" }, "resourceWithQ places the :Q")
        assertTrue(layout.queues.any { it.queueName == "WaitQ" }, "standalone queue placed")
        // V2: responses and counters are deliberately NOT auto-placed (declutter); the author adds them.
        assertTrue(layout.values.isEmpty(), "scaffold omits response/counter read-outs")
        // Invariant: every name the scaffold places exists in the model, so it validates clean.
        assertTrue(layout.validateAgainst(m).isValid, layout.validateAgainst(m).toString())
    }

    // ---- 9A.5: inventory-based validation (CaptureSpec + movable resources) ----

    private val inventory = AnimationInventory(
        queues = listOf("WaitQ", "Worker:Q"),
        resources = listOf("Worker"),
        movableResources = listOf("Forklift"),
        responses = listOf("SystemTime"),
        counters = listOf("NumDone")
    )

    @Test
    fun `a CaptureSpec naming real elements validates clean (9A5)`() {
        val spec = CaptureSpec(
            mode = CaptureMode.SELECTED,
            include = listOf(
                ElementSelector(ElementKind.RESOURCE, "Worker"),
                ElementSelector(ElementKind.MOVABLE_RESOURCE, "Forklift")
            ),
            exclude = listOf(ElementSelector(ElementKind.RESPONSE, "SystemTime"))
        )
        assertTrue(spec.validateAgainst(inventory).isValid, spec.validateAgainst(inventory).toString())
    }

    @Test
    fun `a CaptureSpec selector typo or wrong kind is flagged (9A5)`() {
        val spec = CaptureSpec(
            mode = CaptureMode.SELECTED,
            include = listOf(
                ElementSelector(ElementKind.RESOURCE, "Workr"),        // typo -> Worker
                ElementSelector(ElementKind.QUEUE, "Worker")           // wrong kind (Worker is a resource)
            )
        )
        val report = spec.validateAgainst(inventory)
        assertFalse(report.isValid)
        assertTrue(report.issues.all { it.kind == ValidationIssue.Kind.UNMATCHED_SELECTOR })
        assertTrue(
            report.issues.any { it.name == "Workr" && it.message.contains("Worker") },
            "expected a 'did you mean Worker?' hint: $report"
        )
        assertTrue(report.issues.any { it.name == "Worker" }, "wrong-kind selector is flagged")
    }

    @Test
    fun `a movable-resource layout typo is flagged against the inventory (9A5)`() {
        val layout = Model("x").animation {
            movableResource("Forklfit")    // typo -> Forklift
        }
        val report = layout.validateAgainst(inventory)
        assertFalse(report.isValid)
        assertTrue(report.issues.any {
            it.kind == ValidationIssue.Kind.UNMATCHED_MOVABLE_RESOURCE && it.message.contains("Forklift")
        }, "expected a movable-resource mismatch with a hint: $report")
    }
}
