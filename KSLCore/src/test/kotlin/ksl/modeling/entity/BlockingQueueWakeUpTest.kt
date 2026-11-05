package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Every request that can be filled gets its items at the time they are available, each entity is
 * resumed once, and the same goes for blocked senders. The last tests cover how the queue ranks
 * its receivers.
 */
@DisplayName("Waking entities at a blocking queue")
class BlockingQueueWakeUpTest {

    private abstract class Base(parent: ModelElement) : ProcessModel(parent, "Base") {
        val log = mutableListOf<String>()
        fun note(text: String) { log.add("$text@$time") }
        inner class Item(val tag: String = "") : QObject()
    }

    private fun run(build: (Model) -> Base): List<String> {
        val model = Model("WakeUp${System.nanoTime()}", autoCSVReports = false)
        val base = build(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return base.log
    }

    private class TwoReceivers(parent: ModelElement, first: Boolean, private val list: Boolean) : Base(parent) {
        val q = BlockingQueue<Item>(this, name = "Q").also {
            if (first) it.receiverRequestSelector = it.FirstFillableRequest()
        }

        inner class Receiver(val who: String) : Entity() {
            val p: KSLProcess = process { waitForItems(q, 1); note(who) }
        }

        inner class Sender : Entity() {
            val p: KSLProcess = process {
                delay(5.0)
                if (list) {
                    sendItems(listOf(Item(), Item()), q)
                } else {
                    send(Item(), q)
                    send(Item(), q)
                }
            }
        }

        override fun initialize() {
            activate(Receiver("R1").p)
            activate(Receiver("R2").p)
            activate(Sender().p)
        }
    }

    @Test
    @DisplayName("two items sent together reach two waiting receivers")
    fun twoItemsReachTwoReceivers() {
        val expected = listOf("R1@5.0", "R2@5.0")
        assertEquals(expected, run { TwoReceivers(it, first = false, list = false) })
        assertEquals(expected, run { TwoReceivers(it, first = false, list = true) })
        assertEquals(expected, run { TwoReceivers(it, first = true, list = false) })
    }

    private class ReceiverThenDelay(parent: ModelElement) : Base(parent) {
        val q = BlockingQueue<Item>(this, name = "Q")

        inner class Receiver : Entity() {
            val p: KSLProcess = process {
                waitForItems(q, 1)
                note("got")
                delay(5.0)
                note("delayed")
            }
        }

        inner class Sender : Entity() {
            val p: KSLProcess = process {
                delay(5.0)
                sendItems(listOf(Item(), Item()), q)
            }
        }

        override fun initialize() {
            activate(Receiver().p)
            activate(Sender().p)
        }
    }

    @Test
    @DisplayName("a second item doesn't resume a receiver that has moved on to a delay")
    fun secondItemLeavesDelayAlone() {
        assertEquals(listOf("got@5.0", "delayed@10.0"), run { ReceiverThenDelay(it) })
    }

    private class ReceiverAsksAgain(parent: ModelElement) : Base(parent) {
        val q = BlockingQueue<Item>(this, name = "Q")

        inner class Receiver : Entity() {
            val p: KSLProcess = process {
                for (i in 1..3) {
                    waitForItems(q, 1)
                    note("item$i")
                }
            }
        }

        inner class Sender : Entity() {
            val p: KSLProcess = process {
                delay(5.0)
                send(Item(), q)
                send(Item(), q)
                delay(5.0)
                send(Item(), q)
            }
        }

        override fun initialize() {
            activate(Receiver().p)
            activate(Sender().p)
        }
    }

    @Test
    @DisplayName("a second item doesn't resume a request that nothing can fill")
    fun secondItemLeavesNextRequestAlone() {
        assertEquals(listOf("item1@5.0", "item2@5.0", "item3@10.0"), run { ReceiverAsksAgain(it) })
    }

    private class BlockedSenders(parent: ModelElement, private val numBlocked: Int) : Base(parent) {
        val q = BlockingQueue<Item>(this, capacity = 2, name = "Q")

        inner class Sender(private val who: String, private val at: Double) : Entity() {
            val p: KSLProcess = process {
                delay(at)
                send(Item(), q)
                note(who)
                delay(5.0)
                note("$who delayed")
            }
        }

        inner class Receiver : Entity() {
            val p: KSLProcess = process {
                delay(10.0)
                waitForItems(q, 2)
                note("took two")
            }
        }

        override fun initialize() {
            for (i in 1..(2 + numBlocked)) activate(Sender("S$i", i.toDouble()).p)
            activate(Receiver().p)
        }
    }

    @Test
    @DisplayName("a receiver taking two items wakes a blocked sender once, and wakes the next one too")
    fun takingTwoWakesEachSenderOnce() {
        val one = run { BlockedSenders(it, 1) }
        assertEquals(listOf("took two@10.0", "S3@10.0", "S3 delayed@15.0"), one.filter { "S1" !in it && "S2" !in it })
        val two = run { BlockedSenders(it, 2) }
        assertEquals(
            listOf("took two@10.0", "S3@10.0", "S4@10.0", "S3 delayed@15.0", "S4 delayed@15.0"),
            two.filter { "S1" !in it && "S2" !in it }
        )
    }

    private class OwnItems(parent: ModelElement, first: Boolean) : Base(parent) {
        val q = BlockingQueue<Item>(this, name = "Q").also {
            if (first) it.receiverRequestSelector = it.FirstFillableRequest()
        }

        inner class Receiver(val tag: String) : Entity() {
            val p: KSLProcess = process {
                waitForItems(q, 1, { it.tag == tag })
                note(tag)
            }
        }

        inner class Sender(val at: Double, val tag: String) : Entity() {
            val p: KSLProcess = process {
                delay(at)
                send(Item(tag), q)
            }
        }

        override fun initialize() {
            activate(Receiver("a").p)
            activate(Receiver("b").p)
            activate(Sender(1.0, "b").p)
            activate(Sender(2.0, "a").p)
        }
    }

    @Test
    @DisplayName("the head of the line goes first, and the next request is looked at after it's filled")
    fun nextRequestIsLookedAtAfterAFill() {
        assertEquals(listOf("a@2.0", "b@2.0"), run { OwnItems(it, first = false) })
    }

    @Test
    @DisplayName("FirstFillableRequest lets a later request overtake")
    fun firstFillableOvertakes() {
        assertEquals(listOf("b@1.0", "a@2.0"), run { OwnItems(it, first = true) })
    }

    private class TerminatedInLine(parent: ModelElement) : Base(parent) {
        val q = BlockingQueue<Item>(this, capacity = 1, name = "Q")
        lateinit var blocked: Entity

        inner class Sender(private val who: String, private val at: Double) : Entity() {
            val p: KSLProcess = process {
                delay(at)
                send(Item(), q)
                note(who)
            }
        }

        inner class Receiver : Entity() {
            val p: KSLProcess = process {
                delay(10.0)
                waitForItems(q, 1)
                note("took one")
            }
        }

        override fun initialize() {
            activate(Sender("first", 1.0).p)
            val second = Sender("second", 2.0)
            blocked = second
            activate(second.p)
            activate(Sender("third", 3.0).p)
            activate(Receiver().p)
            schedule<Nothing>({ blocked.terminateProcess() }, 5.0)
        }
    }

    @Test
    @DisplayName("a blocked sender that is terminated doesn't keep its place in line")
    fun terminatedSenderLeavesTheLine() {
        assertEquals(listOf("first@1.0", "took one@10.0", "third@10.0"), run { TerminatedInLine(it) })
    }

    private class TakesTheSlot(parent: ModelElement) : Base(parent) {
        val q = BlockingQueue<Item>(this, capacity = 1, name = "Q")

        inner class Sender(private val who: String, private val at: Double) : Entity() {
            val p: KSLProcess = process {
                delay(at)
                send(Item(), q)
                note(who)
            }
        }

        inner class Swapper : Entity() {
            val p: KSLProcess = process {
                delay(5.0)
                waitForItems(q, 1)
                send(Item(), q)
                note("swapped")
                delay(5.0)
                waitForItems(q, 1)
                note("took")
            }
        }

        override fun initialize() {
            activate(Sender("S1", 1.0).p)
            activate(Sender("S2", 2.0).p)
            activate(Swapper().p)
        }
    }

    @Test
    @DisplayName("a blocked sender told to resume goes on waiting if another entity takes the slot first")
    fun senderKeepsWaitingWhenTheSlotIsTaken() {
        assertEquals(listOf("S1@1.0", "swapped@5.0", "took@10.0", "S2@10.0"), run { TakesTheSlot(it) })
    }

    private class Ranked(
        parent: ModelElement,
        discipline: Queue.Discipline,
        private val any: Boolean,
        private val explicitForA: Int? = null
    ) : Base(parent) {
        val q = BlockingQueue<Item>(this, name = "Q").also { it.requestQ.initialDiscipline = discipline }

        inner class Receiver(val who: String, own: Int, val at: Double) : Entity() {
            init { priority = own }
            val p: KSLProcess = process {
                delay(at)
                if (who == "A" && explicitForA != null) {
                    waitForItems(q, 1, blockingPriority = explicitForA)
                } else if (any) {
                    waitForAnyItems(q, { true })
                } else {
                    waitForItems(q, 1)
                }
                note("$who/$priority")
            }
        }

        inner class Sender(val at: Double) : Entity() {
            val p: KSLProcess = process {
                delay(at)
                send(Item(), q)
            }
        }

        override fun initialize() {
            activate(Receiver("A", 5, 1.0).p)
            activate(Receiver("B", 1, 2.0).p)
            activate(Receiver("C", 3, 3.0).p)
            for (t in listOf(10.0, 11.0, 12.0)) activate(Sender(t).p)
        }
    }

    @Test
    @DisplayName("a ranked request queue ranks receivers by their own priority by default")
    fun rankedQueueUsesEntityPriority() {
        val expected = listOf("B/1@10.0", "C/3@11.0", "A/5@12.0")
        assertEquals(expected, run { Ranked(it, Queue.Discipline.RANKED, any = false) })
        assertEquals(expected, run { Ranked(it, Queue.Discipline.RANKED, any = true) })
    }

    @Test
    @DisplayName("a FIFO request queue ignores priority")
    fun fifoQueueIgnoresPriority() {
        assertEquals(
            listOf("A/5@10.0", "B/1@11.0", "C/3@12.0"),
            run { Ranked(it, Queue.Discipline.FIFO, any = false) }
        )
    }

    @Test
    @DisplayName("a priority supplied to the wait ranks the request and leaves the entity's alone")
    fun explicitPriorityRanksTheRequestOnly() {
        assertEquals(
            listOf("A/5@10.0", "B/1@11.0", "C/3@12.0"),
            run { Ranked(it, Queue.Discipline.RANKED, any = false, explicitForA = 0) }
        )
    }
}
