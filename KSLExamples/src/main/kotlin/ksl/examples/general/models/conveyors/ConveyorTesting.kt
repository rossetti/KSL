package ksl.examples.general.models.conveyors

import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Identity


fun main() {
//TODO the main run
    //   partitionTest()
//    buildTest()
//    runConveyorTest(Conveyor.Type.ACCUMULATING)
    runConveyorTest2(Conveyor.Type.ACCUMULATING)
//    runConveyorTest3(Conveyor.Type.ACCUMULATING)
//    runConveyorTest4(Conveyor.Type.ACCUMULATING)
 //   runConveyorTest(Conveyor.Type.NON_ACCUMULATING)
//    blockedCellsTest()
}

fun partitionTest() {
    val list = listOf<Int>(1, 2, 3, 4)
    val (f, s) = list.partition { it <= 1 }
    val rf = f.asReversed()
    println(rf.joinToString())
    val rs = s.asReversed()
    println(rs.joinToString())
    val n = rf + rs
    println(n.joinToString())
}

fun buildTest() {
    val i1 = Identity(name = "A")
    val i2 = Identity(name = "B")
    val i3 = Identity(name = "C")
    val c = Conveyor.builder(Model())
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(3.0)
        .cellSize(1)
        .firstSegment(i1, i2, 10)
        .nextSegment(i3, 20)
        .build()

    println(c)
}

class TestConveyor(parent: ModelElement, conveyorType: Conveyor.Type) : ProcessModel(parent) {

    val conveyor: Conveyor
    val i1 = Identity(name = "A")
    val i2 = Identity(name = "B")
    val i3 = Identity(name = "C")

    init {
        conveyor = Conveyor.builder(this)
            .conveyorType(conveyorType)
            .velocity(1.0)
            .cellSize(1)
            .maxCellsAllowed(2)
            .firstSegment(i1, i2, 10)
            .nextSegment(i3, 20)
            .build()
        println(conveyor)
        println()
    }

    override fun initialize() {
        val p1 = Part("Part1")
        activate(p1.conveyingProcess)
        val p2 = Part("Part2")
        activate(p2.conveyingProcess, timeUntilActivation = 0.1)
        val p3 = Part("Part3")
        activate(p3.conveyingProcess, timeUntilActivation = 0.1)
        val p4 = Part("Part4")
        activate(p4.conveyingProcess, timeUntilActivation = 10.0)
    }

    private inner class Part(name: String? = null) : Entity(name) {
        val conveyingProcess: KSLProcess = process("test") {
            println("${entity.name}: time = $time before access at ${i1.name}")
            var amt = 1
            if (entity.name == "Part1") {
                amt = 2
            }
            val a = if (entity.name == "Part4") {
                requestConveyor(conveyor, i2, amt)
            } else {
                requestConveyor(conveyor, i1, amt)
            }
            println("${entity.name}: time = $time after access")
//                       delay(10.0)
            timeStamp = time
            if (entity.name == "Part1") {
                println("${entity.name}: time = $time before ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            } else if (entity.name == "Part4") {
                println("${entity.name}: time = $time before ride to ${i3.name}")
                rideConveyor(a, i3)
                println("${entity.name}: time = $time after ride to ${i3.name}")
            } else {
                println("${entity.name}: time = $time before ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            }
            println("${entity.name}: The riding time was ${time - timeStamp}")
            delay(2.5)
//            delay(10.0)
//            println("${entity.name}: time = $time after second delay of 10.0 ")
            println("${entity.name}: time = $time before exit ")
            exitConveyor(a)
            println("${entity.name}: time = $time after exit ")
        }

    }

}

fun runConveyorTest(conveyorType: Conveyor.Type) {
    val m = Model()
    val test = TestConveyor(m, conveyorType)
    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
    m.print()
}

fun runConveyorTest2(conveyorType: Conveyor.Type) {
    val m = Model()
    val test = TestConveyor2(m, conveyorType)
    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
    m.print()
}

fun runConveyorTest3(conveyorType: Conveyor.Type) {
    val m = Model()
    val test = TestConveyor3(m, conveyorType)
    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
    m.print()
}

fun runConveyorTest4(conveyorType: Conveyor.Type) {
    val m = Model()
    val test = TestConveyor4(m, conveyorType)
    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
    m.print()
}

class TestConveyor2(parent: ModelElement, conveyorType: Conveyor.Type) : ProcessModel(parent) {

    val conveyor: Conveyor
    val i1 = Identity(name = "A")
    val i2 = Identity(name = "B")
    val i3 = Identity(name = "C")

    init {
        conveyor = Conveyor.builder(this)
            .conveyorType(conveyorType)
            .velocity(1.0)
            .cellSize(1)
            .maxCellsAllowed(2)
            .firstSegment(i1, i2, 10)
            .nextSegment(i3, 20)
            .nextSegment(i1, 5)
            .build()
        println(conveyor)
        println()
    }

    override fun initialize() {
        val p1 = Part("Part1")
        activate(p1.conveyingProcess)
        val p2 = Part("Part2")
        activate(p2.conveyingProcess, timeUntilActivation = 0.1)
        val p3 = Part("Part3")
        activate(p3.conveyingProcess, timeUntilActivation = 0.1)
        val p4 = Part("Part4")
        activate(p4.conveyingProcess, timeUntilActivation = 10.0)
    }

    private inner class Part(name: String? = null) : Entity(name) {
        val conveyingProcess: KSLProcess = process("test") {
            println("${entity.name}: time = $time before access at ${i1.name}")
            var amt = 1
            if (entity.name == "Part1") {
                amt = 2
            }
            val a = if (entity.name == "Part4") {
                requestConveyor(conveyor, i2, amt)
            } else {
                requestConveyor(conveyor, i1, amt)
            }
            println("${entity.name}: time = $time after access")
//                       delay(10.0)
            timeStamp = time
            if (entity.name == "Part1") {
                println("${entity.name}: time = $time before ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            } else if (entity.name == "Part4") {
                println("${entity.name}: time = $time before ride to ${i1.name}")
                rideConveyor(a, i1)
                println("${entity.name}: time = $time after ride to ${i1.name}")
            } else {
                println("${entity.name}: time = $time before ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            }
            println("${entity.name}: The riding time was ${time - timeStamp}")
//            delay(2.5)
//            delay(10.0)
//            println("${entity.name}: time = $time after second delay of 10.0 ")
            println("${entity.name}: time = $time before exit ")
            exitConveyor(a)
            println("${entity.name}: time = $time after exit ")
        }

    }

}

class TestConveyor3(parent: ModelElement, conveyorType: Conveyor.Type) : ProcessModel(parent) {

    val conveyor: Conveyor
    val i1 = Identity(name = "A")
    val i2 = Identity(name = "B")
    val i3 = Identity(name = "C")

    init {
        conveyor = Conveyor.builder(this)
            .conveyorType(conveyorType)
            .velocity(1.0)
            .cellSize(1)
            .maxCellsAllowed(2)
            .firstSegment(i1, i2, 10)
            .nextSegment(i3, 20)
            .nextSegment(i1, 5)
            .build()
        println(conveyor)
        println()
    }

    override fun initialize() {
        val p1 = Part("Part1")
        activate(p1.conveyingProcess)
        val p2 = Part("Part2")
        activate(p2.conveyingProcess, timeUntilActivation = 0.1)
        val p3 = Part("Part3")
        activate(p3.conveyingProcess, timeUntilActivation = 0.1)
        val p4 = Part("Part4")
        activate(p4.conveyingProcess, timeUntilActivation = 10.0)
    }

    private inner class Part(name: String? = null) : Entity(name) {
        val conveyingProcess: KSLProcess = process("test") {
            println("${entity.name}: time = $time before access at ${i1.name}")
            var amt = 1
            if (entity.name == "Part1") {
                amt = 2
            }
            val a = if (entity.name == "Part4") {
                requestConveyor(conveyor, i2, amt)
            } else {
                requestConveyor(conveyor, i1, amt)
            }
            println("${entity.name}: time = $time after access")
//                       delay(10.0)
            timeStamp = time
            if (entity.name == "Part1") {
                println("${entity.name}: time = $time before ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            } else if (entity.name == "Part4") {
                println("${entity.name}: time = $time before ride to ${i1.name}")
                rideConveyor(a, i1)
                println("${entity.name}: time = $time after ride to ${i1.name}")
            } else {
                println("${entity.name}: time = $time before ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            }
            println("${entity.name}: The riding time was ${time - timeStamp}")
            delay(2.5)
//            delay(10.0)
//            println("${entity.name}: time = $time after second delay of 10.0 ")
            if (entity.name == "Part4") {
                println("${entity.name}: time = $time continue to ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            }
            println("${entity.name}: time = $time before exit ")
            exitConveyor(a)
            println("${entity.name}: time = $time after exit ")
        }

    }

}

class TestConveyor4(parent: ModelElement, conveyorType: Conveyor.Type) : ProcessModel(parent) {

    val conveyor: Conveyor
    val i1 = Identity(name = "A")
    val i2 = Identity(name = "B")
    val i3 = Identity(name = "C")

    init {
        conveyor = Conveyor.builder(this)
            .conveyorType(conveyorType)
            .velocity(1.0)
            .cellSize(1)
            .maxCellsAllowed(2)
            .firstSegment(i1, i2, 10)
            .nextSegment(i3, 20)
            .nextSegment(i1, 5)
            .build()
        println(conveyor)
        println()
    }

    override fun initialize() {
        val p1 = Part("Part1")
        activate(p1.conveyingProcess)
        val p2 = Part("Part2")
        activate(p2.conveyingProcess, timeUntilActivation = 0.1)
        val p3 = Part("Part3")
        activate(p3.conveyingProcess, timeUntilActivation = 0.1)
        val p4 = Part("Part4")
        activate(p4.conveyingProcess, timeUntilActivation = 10.0)
    }

    private inner class Part(name: String? = null) : Entity(name) {
        val conveyingProcess: KSLProcess = process("test") {
            println("${entity.name}: time = $time before access at ${i1.name}")
            var amt = 1
            if (entity.name == "Part1") {
                amt = 2
            }
            val a = if (entity.name == "Part4") {
                requestConveyor(conveyor, i2, amt)
            } else {
                requestConveyor(conveyor, i1, amt)
            }
            println("${entity.name}: time = $time after access")
//                       delay(10.0)
            timeStamp = time
            if (entity.name == "Part1") {
                delay(50.0)
                println("${entity.name}: time = $time before ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            } else if (entity.name == "Part4") {
                println("${entity.name}: time = $time before ride to ${i1.name}")
                rideConveyor(a, i1)
                println("${entity.name}: time = $time after ride to ${i1.name}")
            } else {
                println("${entity.name}: time = $time before ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            }
            println("${entity.name}: The riding time was ${time - timeStamp}")
            delay(2.5)
//            delay(10.0)
//            println("${entity.name}: time = $time after second delay of 10.0 ")
            if (entity.name == "Part4") {
                println("${entity.name}: time = $time continue to ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            }
            println("${entity.name}: time = $time before exit ")
            exitConveyor(a)
            println("${entity.name}: time = $time after exit ")
        }

    }

}