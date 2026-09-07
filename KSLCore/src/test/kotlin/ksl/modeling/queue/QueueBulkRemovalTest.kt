package ksl.modeling.queue

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 *  Covers the bulk removal family of [Queue] — the predicate overloads and the
 *  two `removeAll` overloads. Nothing in the repository calls any of them, so
 *  these tests are the whole of their coverage.
 *
 *  Two historical defects motivate the specific cases. The predicate overload
 *  used to traverse live indices while removing, which skipped the element after
 *  every match and then indexed past the shrunken list; the adjacent-match and
 *  all-match cases are the ones that catch a regression of it. Both `removeAll`
 *  overloads used to assign rather than accumulate their return flag, so a
 *  collection whose last element was absent reported no change however much had
 *  been removed; the first-present-last-absent case is what pins that.
 *
 *  Every scenario runs inside a live replication, because enqueueing touches
 *  model time and the number-in-queue response.
 */
class QueueBulkRemovalTest {

    /**
     *  A queue that exposes the current value of its number-in-queue response so
     *  a test can assert that the count and the contents stayed together.
     */
    private class TestQueue(
        parent: ModelElement,
        name: String
    ) : Queue<ModelElement.QObject>(parent, name) {

        val currentNumInQ: Double
            get() = myNumInQ.value
    }

    /**
     *  Runs [scenario] once, during the replication's initialization, and gives
     *  it the queue plus the helpers for filling and reading it.
     */
    private class Harness(
        parent: ModelElement,
        private val scenario: (Harness) -> Unit
    ) : ModelElement(parent, "BulkRemovalHarness") {

        val queue = TestQueue(this, "TestQ")

        /** Enqueues one fresh QObject per name, in the order given. */
        fun fill(vararg itemNames: String): List<QObject> {
            val items = itemNames.map { QObject(it) }
            for (item in items) {
                queue.enqueue(item)
            }
            return items
        }

        /** A QObject that is never enqueued here. */
        fun stranger(itemName: String): QObject = QObject(itemName)

        /** The queue's contents, by name, in list order. */
        fun contents(): List<String> = queue.map { it.name }

        override fun initialize() {
            scenario(this)
        }
    }

    /**
     *  Builds a one-replication model around [scenario] and runs it. Anything the
     *  scenario throws surfaces out of this call.
     */
    private fun runScenario(scenario: (Harness) -> Unit) {
        val model = Model("QueueBulkRemovalTest")
        Harness(model, scenario)
        model.numberOfReplications = 1
        model.lengthOfReplication = 1.0
        model.simulate()
    }

    // ── remove(predicate) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a predicate matching nothing removes nothing and leaves the queue alone")
    fun removePredicateMatchingNothing() {
        var removed: List<String> = listOf("unset")
        var remaining: List<String> = emptyList()
        var numInQ = -1.0
        var size = -1
        runScenario { h ->
            h.fill("A", "B", "C", "D")
            removed = h.queue.remove({ it.name == "Z" }, false).map { it.name }
            remaining = h.contents()
            numInQ = h.queue.currentNumInQ
            size = h.queue.size
        }
        assertEquals(emptyList<String>(), removed, "nothing matched, so nothing should be returned")
        assertEquals(listOf("A", "B", "C", "D"), remaining, "the queue should be untouched")
        assertEquals(size.toDouble(), numInQ, "numInQ drifted from the contents")
    }

    @Test
    @DisplayName("a predicate matching one middle element removes exactly it")
    fun removePredicateMatchingMiddleElement() {
        var removed: List<String> = emptyList()
        var remaining: List<String> = emptyList()
        var numInQ = -1.0
        var size = -1
        runScenario { h ->
            h.fill("A", "B", "C", "D")
            removed = h.queue.remove({ it.name == "B" }, false).map { it.name }
            remaining = h.contents()
            numInQ = h.queue.currentNumInQ
            size = h.queue.size
        }
        assertEquals(listOf("B"), removed, "the matched element should be returned")
        assertEquals(listOf("A", "C", "D"), remaining, "exactly the matched element should be gone")
        assertEquals(size.toDouble(), numInQ, "numInQ drifted from the contents")
    }

    @Test
    @DisplayName("a predicate matching two adjacent elements removes both")
    fun removePredicateMatchingAdjacentElements() {
        var removed: List<String> = emptyList()
        var remaining: List<String> = emptyList()
        var numInQ = -1.0
        var size = -1
        runScenario { h ->
            h.fill("A", "B", "C", "D")
            removed = h.queue.remove({ it.name == "B" || it.name == "C" }, false).map { it.name }
            remaining = h.contents()
            numInQ = h.queue.currentNumInQ
            size = h.queue.size
        }
        // The element following a removed one is the case a live-index traversal
        // skips, so this is the assertion that catches that defect returning.
        assertEquals(listOf("B", "C"), removed, "both matches should be returned, in list order")
        assertEquals(listOf("A", "D"), remaining, "both matches should be gone")
        assertEquals(size.toDouble(), numInQ, "numInQ drifted from the contents")
    }

    @Test
    @DisplayName("a predicate matching the final element removes exactly it")
    fun removePredicateMatchingFinalElement() {
        var removed: List<String> = emptyList()
        var remaining: List<String> = emptyList()
        var numInQ = -1.0
        var size = -1
        runScenario { h ->
            h.fill("A", "B", "C", "D")
            removed = h.queue.remove({ it.name == "D" }, false).map { it.name }
            remaining = h.contents()
            numInQ = h.queue.currentNumInQ
            size = h.queue.size
        }
        assertEquals(listOf("D"), removed, "the matched element should be returned")
        assertEquals(listOf("A", "B", "C"), remaining, "exactly the matched element should be gone")
        assertEquals(size.toDouble(), numInQ, "numInQ drifted from the contents")
    }

    @Test
    @DisplayName("a predicate matching every element empties the queue")
    fun removePredicateMatchingEveryElement() {
        var removed: List<String> = emptyList()
        var remaining: List<String> = listOf("unset")
        var numInQ = -1.0
        var size = -1
        runScenario { h ->
            h.fill("A", "B", "C", "D")
            removed = h.queue.remove({ true }, false).map { it.name }
            remaining = h.contents()
            numInQ = h.queue.currentNumInQ
            size = h.queue.size
        }
        assertEquals(listOf("A", "B", "C", "D"), removed, "every element should be returned, in list order")
        assertEquals(emptyList<String>(), remaining, "the queue should be empty")
        assertEquals(0, size, "the queue should be empty")
        assertEquals(size.toDouble(), numInQ, "numInQ drifted from the contents")
    }

    @Test
    @DisplayName("the java Predicate overload behaves like the lambda overload")
    fun removeJavaPredicateOverload() {
        var removed: List<String> = emptyList()
        var remaining: List<String> = emptyList()
        runScenario { h ->
            h.fill("A", "B", "C", "D")
            val predicate = java.util.function.Predicate<ModelElement.QObject> {
                it.name == "B" || it.name == "C"
            }
            removed = h.queue.remove(predicate, false).map { it.name }
            remaining = h.contents()
        }
        assertEquals(listOf("B", "C"), removed, "both matches should be returned, in list order")
        assertEquals(listOf("A", "D"), remaining, "both matches should be gone")
    }

    // ── removeAll ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("removeAll reports true when the first element is present and the last is not")
    fun removeAllCollectionWithFirstPresentLastAbsent() {
        var result = false
        var remaining: List<String> = emptyList()
        var numInQ = -1.0
        var size = -1
        runScenario { h ->
            val items = h.fill("A", "B", "C", "D")
            // The queue plainly changes, but only the last element of the
            // argument decided the answer before this was fixed.
            result = h.queue.removeAll(listOf(items[0], h.stranger("stranger")), false)
            remaining = h.contents()
            numInQ = h.queue.currentNumInQ
            size = h.queue.size
        }
        assertTrue(result, "the queue changed, so removeAll should report true")
        assertEquals(listOf("B", "C", "D"), remaining, "the present element should be gone")
        assertEquals(size.toDouble(), numInQ, "numInQ drifted from the contents")
    }

    @Test
    @DisplayName("removeAll reports false when none of the collection is present")
    fun removeAllCollectionWithNonePresent() {
        var result = true
        var remaining: List<String> = emptyList()
        runScenario { h ->
            h.fill("A", "B", "C", "D")
            result = h.queue.removeAll(listOf(h.stranger("x"), h.stranger("y")), false)
            remaining = h.contents()
        }
        assertFalse(result, "nothing was removed, so removeAll should report false")
        assertEquals(listOf("A", "B", "C", "D"), remaining, "the queue should be untouched")
    }

    @Test
    @DisplayName("the iterator overload reports true when the first element is present and the last is not")
    fun removeAllIteratorWithFirstPresentLastAbsent() {
        var result = false
        var remaining: List<String> = emptyList()
        var numInQ = -1.0
        var size = -1
        runScenario { h ->
            val items = h.fill("A", "B", "C", "D")
            result = h.queue.removeAll(listOf(items[0], h.stranger("stranger")).iterator(), false)
            remaining = h.contents()
            numInQ = h.queue.currentNumInQ
            size = h.queue.size
        }
        assertTrue(result, "the queue changed, so removeAll should report true")
        assertEquals(listOf("B", "C", "D"), remaining, "the present element should be gone")
        assertEquals(size.toDouble(), numInQ, "numInQ drifted from the contents")
    }

    @Test
    @DisplayName("the iterator overload reports false when none of the collection is present")
    fun removeAllIteratorWithNonePresent() {
        var result = true
        var remaining: List<String> = emptyList()
        runScenario { h ->
            h.fill("A", "B", "C", "D")
            result = h.queue.removeAll(listOf(h.stranger("x"), h.stranger("y")).iterator(), false)
            remaining = h.contents()
        }
        assertFalse(result, "nothing was removed, so removeAll should report false")
        assertEquals(listOf("A", "B", "C", "D"), remaining, "the queue should be untouched")
    }
}
