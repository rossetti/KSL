package ksl.examples.general.lectures.week6

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

fun main() {
    val m = Model("Scheduling Example")
    SchedulingEventExamples(m.model)
    m.lengthOfReplication = 100.0
    m.simulate()
}

class SchedulingEventExamples (parent: ModelElement, name: String? = null) :
    ModelElement(parent, name) {
//    private val myEventActionOne: EventActionOne = EventActionOne()
//    private val myEventActionTwo: EventActionTwo = EventActionTwo()

        private val funReference = this::actionTwoEvent

    override fun initialize() {
        // schedule a type 1 event at time 10.0
        schedule(this::actionOneEvent, 10.0)
        // schedule an event that uses myEventAction for time 20.0
        schedule(funReference, 20.0, message = 10000)
    }

    private fun actionOneEvent(event: KSLEvent<Nothing>){
        println("EventActionOne at time : $time")
    }

//    private inner class EventActionOne : EventAction<Nothing>() {
//        override fun action(event: KSLEvent<Nothing>) {
//            println("EventActionOne at time : $time")
//        }
//    }

    private fun actionTwoEvent(event: KSLEvent<Long>) {
        println("EventActionTwo at time : $time with ${event.message}")
        // schedule a type 1 event for time t + 15
        schedule(this::actionOneEvent, 15.0)
        // reschedule the EventAction event for t + 20
        schedule(this::actionTwoEvent, 20.0, message = 10000)
    }

//    private inner class EventActionTwo : EventAction<Nothing>() {
//        override fun action(event: KSLEvent<Nothing>) {
//            println("EventActionTwo at time : $time")
//            // schedule a type 1 event for time t + 15
//            schedule(this@SchedulingEventExamples::actionOneEvent, 15.0)
//            // reschedule the EventAction event for t + 20
//            schedule(myEventActionTwo, 20.0)
//        }
//    }
}