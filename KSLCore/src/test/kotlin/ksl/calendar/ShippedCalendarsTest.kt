package ksl.calendar

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every shipped calendar runs a model in the same order as the default. */
@DisplayName("The shipped event calendars")
class ShippedCalendarsTest {

    /**
     * A queue, plus what a calendar has to get right: events tied in time and priority, a higher
     * priority scheduled later at the same time, and canceled events.
     */
    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val log = mutableListOf<String>()
        private val server = ResourceWithQ(this, "Server", capacity = 2)
        private val gap = RandomVariable(this, ExponentialRV(2.0, 1))
        private val service = RandomVariable(this, ExponentialRV(3.0, 2))

        private var numCreated = 0

        inner class Customer : Entity() {
            // not the entity's id, which is numbered across every model in the JVM
            private val number = ++numCreated
            val p: KSLProcess = process {
                log.add("arrive $number at $time")
                use(server, delayDuration = service)
                log.add("leave $number at $time")
            }
        }

        private fun arrive(event: KSLEvent<Nothing>) {
            activate(Customer().p)
            schedule(::arrive, gap)
        }

        private fun mark(event: KSLEvent<String>) {
            log.add("${event.message} at $time")
        }

        override fun initialize() {
            numCreated = 0
            schedule(::arrive, gap)
            for (i in 1..20) {
                val t = i * 5.0
                schedule(::mark, t, "first of three, tied $i")
                schedule(::mark, t, "second of three, tied $i")
                schedule(::mark, t, "ahead of the others $i", priority = KSLEvent.VERY_HIGH_PRIORITY)
                schedule(::mark, t, "canceled $i").cancel = true
            }
        }
    }

    private fun run(calendar: CalendarIfc): List<String> {
        val model = Model("Calendar${System.nanoTime()}", autoCSVReports = false, eventCalendar = calendar)
        val shop = Shop(model)
        val all = mutableListOf<String>()
        model.numberOfReplications = 3
        model.lengthOfReplication = 200.0
        model.simulate()
        all.addAll(shop.log)
        return all
    }

    @Test
    @DisplayName("each gives the default's event order, across replications")
    fun sameOrderAsTheDefault() {
        val expected = run(PriorityQueueEventCalendar())
        assertTrue(expected.size > 100, "the model should do something: ${expected.size}")
        assertTrue(expected.none { it.startsWith("canceled") }, "a canceled event ran")
        assertEquals(expected, run(TreeSetEventCalendar()), "TreeSetEventCalendar")
        assertEquals(expected, run(SkewHeapEventCalendar()), "SkewHeapEventCalendar")
        assertEquals(expected, run(LinkedListEventCalendar()), "LinkedListEventCalendar")
    }

    @Test
    @DisplayName("each is empty after clear")
    fun clearEmptiesTheCalendar() {
        for (calendar in listOf(
            PriorityQueueEventCalendar(), TreeSetEventCalendar(), SkewHeapEventCalendar(), LinkedListEventCalendar()
        )) {
            run(calendar)
            calendar.clear()
            assertTrue(calendar.isEmpty, calendar::class.simpleName)
            assertEquals(0, calendar.size(), calendar::class.simpleName)
        }
    }
}
