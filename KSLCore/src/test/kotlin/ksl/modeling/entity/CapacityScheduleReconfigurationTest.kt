package ksl.modeling.entity

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A capacity schedule's length is the total of its item durations, so clearing the items has to
 * return it to zero. It did not: `clearSchedule` emptied the item list and left the length at
 * whatever the removed items summed to, and the next item added was placed at that offset,
 * because an item's start time is the schedule length at the moment it is added.
 *
 * The consequence was silent and total. Assigning `capacityScheduleData` a second time -- which
 * clears and re-adds -- produced a schedule whose first change was due only after the previous
 * schedule would have ended, so within any replication no capacity change occurred at all and
 * the resource sat at its initial capacity for the whole run. Nothing threw and nothing warned;
 * the schedule simply had no effect.
 *
 * That matters wherever a schedule is configured from a property setter rather than once at
 * construction -- a staffing control, a scenario sweep, a simulation-optimization input -- which
 * is precisely the case where the second assignment is the one that counts.
 */
class CapacityScheduleReconfigurationTest {

    private companion object {
        const val ITEM_DURATION = 10.0
        val CAPACITIES = intArrayOf(1, 2, 3)
        /** The time average of 1, 2 and 3 held for equal durations. */
        const val EXPECTED_TIME_AVERAGE_CAPACITY = 2.0
        const val INITIAL_CAPACITY = 5
    }

    private class Fixture(parent: ModelElement, assignments: Int) : ModelElement(parent, "Fixture") {
        val resource = Resource(this, name = "R", capacity = INITIAL_CAPACITY)
        val schedule = CapacitySchedule(this, 0.0, name = "S")

        init {
            resource.useSchedule(schedule, CapacityChangeRule.WAIT)
            repeat(assignments) {
                schedule.capacityScheduleData = CapacityScheduleData(CAPACITIES, ITEM_DURATION)
            }
        }
    }

    /** Runs one replication over the schedule's span and returns the time-average capacity. */
    private fun timeAverageCapacity(assignments: Int): Pair<Double, Double> {
        val model = Model("Reconfigure$assignments", autoCSVReports = false)
        model.numberOfReplications = 1
        model.lengthOfReplication = CAPACITIES.size * ITEM_DURATION
        val fixture = Fixture(model, assignments)
        model.simulate()
        val active = model.responses.first { it.name == "R:NumActiveUnits" }
        return active.acrossReplicationStatistic.average to fixture.schedule.scheduleLength
    }

    @Test
    @DisplayName("Re-assigning the same schedule data leaves the schedule unchanged")
    fun reassigningScheduleDataIsIdempotent() {
        val (onceAverage, onceLength) = timeAverageCapacity(assignments = 1)
        assertEquals(CAPACITIES.size * ITEM_DURATION, onceLength, 1.0e-12)
        assertEquals(EXPECTED_TIME_AVERAGE_CAPACITY, onceAverage, 1.0e-12) {
            "A schedule assigned once did not drive the capacity"
        }

        for (assignments in 2..4) {
            val (average, length) = timeAverageCapacity(assignments)
            assertEquals(onceLength, length, 1.0e-12) {
                "After $assignments assignments the schedule length had grown to $length"
            }
            assertEquals(onceAverage, average, 1.0e-12) {
                "After $assignments assignments the time-average capacity was $average rather " +
                    "than $onceAverage; the schedule had been pushed past the replication"
            }
        }
    }

    @Test
    @DisplayName("Clearing a schedule returns its length to zero")
    fun clearingReturnsTheLengthToZero() {
        val model = Model("ClearLength", autoCSVReports = false)
        val schedule = CapacitySchedule(model, 0.0, name = "S")
        schedule.addItem(capacity = 1, duration = ITEM_DURATION)
        schedule.addItem(capacity = 2, duration = ITEM_DURATION)
        assertEquals(2 * ITEM_DURATION, schedule.scheduleLength, 1.0e-12)

        schedule.clearSchedule()
        assertEquals(0.0, schedule.scheduleLength, 1.0e-12) {
            "An empty schedule reported a length of ${schedule.scheduleLength}"
        }

        // and the next item added starts at the beginning, not after the removed items
        schedule.addItem(capacity = 3, duration = ITEM_DURATION)
        assertEquals(ITEM_DURATION, schedule.scheduleLength, 1.0e-12)
    }

    @Test
    @DisplayName("A schedule replaced with different data takes effect")
    fun replacementDataTakesEffect() {
        val model = Model("Replace", autoCSVReports = false)
        model.numberOfReplications = 1
        model.lengthOfReplication = 3 * ITEM_DURATION
        val fixture = Fixture(model, assignments = 1)
        // replace with a different profile; its average differs from both the first profile's
        // and the resource's initial capacity, so neither can be mistaken for success
        fixture.schedule.capacityScheduleData =
            CapacityScheduleData(intArrayOf(4, 4, 10), ITEM_DURATION)
        model.simulate()
        val average = model.responses.first { it.name == "R:NumActiveUnits" }
            .acrossReplicationStatistic.average
        assertEquals(6.0, average, 1.0e-12) {
            "The replacement schedule did not take effect: time-average capacity was $average"
        }
    }
}
