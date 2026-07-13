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
 //   runConveyorTest2(Conveyor.Type.ACCUMULATING)

//    runConveyorTest3(Conveyor.Type.ACCUMULATING)
//    runConveyorTest4(Conveyor.Type.ACCUMULATING)
 //   runConveyorTest(Conveyor.Type.NON_ACCUMULATING)
//    blockedCellsTest()

 //   runConveyorTest5(Conveyor.Type.ACCUMULATING)
    runConveyorTest5(Conveyor.Type.NON_ACCUMULATING)
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
    val i1 = "A"
    val i2 = "B"
    val i3 = "C"
    val c = Conveyor.builder(Model())
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(3.0)
        .cellSize(1)
        .firstSegment(i1, i2, 10)
        .nextSegment(i3, 20)
//        .nextSegment(i1, 15)
        .build()
    println(c)

    val entryCells = c.entryCells
    val i1Cells = entryCells[i1]!!.previousCells(4)
    println()
    println("previous cells for $i1")
    i1Cells.forEach { println(it) }
    val i2Cells = entryCells[i2]!!.previousCells(30)
    println()
    println("previous cells for $i2")
    i2Cells.forEach { println(it) }
}

/**
 * A basic demonstration of the KSL [Conveyor] feature (process view). Builds a conveyor of the given
 * accumulating or non-accumulating type over two segments (A to B to C) and sends parts through the
 * `requestConveyor` / `rideConveyor` / `exitConveyor` cycle, so parts occupy conveyor cells while riding
 * between named access points and contend for space by cell count. Console-traced. [TestConveyor2]
 * through [TestConveyor5] are progressive variants (looping segments, multi-hop rides, holding while
 * blocked, and congestion).
 */
class TestConveyor(parent: ModelElement, conveyorType: Conveyor.Type) : ProcessModel(parent) {

    val conveyor: Conveyor
    val i1 = "A"
    val i2 = "B"
    val i3 = "C"

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
            println("${entity.name}: time = $time before access at ${i1}")
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
                println("${entity.name}: time = $time before ride to ${i2}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2}")
            } else if (entity.name == "Part4") {
                println("${entity.name}: time = $time before ride to ${i3}")
                rideConveyor(a, i3)
                println("${entity.name}: time = $time after ride to ${i3}")
            } else {
                println("${entity.name}: time = $time before ride to ${i2}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2}")
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

fun runConveyorTest5(conveyorType: Conveyor.Type) {
    val m = Model()
    val test = TestConveyor5(m, conveyorType)
    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
    m.print()
}

/**
 * A [Conveyor] demo variant of [TestConveyor] with a looping topology — a third segment returns from C
 * back to A (A to B to C to A) — so a part can ride around the loop (here Part4 rides C to A).
 * Console-traced.
 */
class TestConveyor2(parent: ModelElement, conveyorType: Conveyor.Type) : ProcessModel(parent) {

    val conveyor: Conveyor
    val i1 = "A"
    val i2 = "B"
    val i3 = "C"

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
            println("${entity.name}: time = $time before access at ${i1}")
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
                println("${entity.name}: time = $time before ride to ${i2}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2}")
            } else if (entity.name == "Part4") {
                println("${entity.name}: time = $time before ride to ${i1}")
                rideConveyor(a, i1)
                println("${entity.name}: time = $time after ride to ${i1}")
            } else {
                println("${entity.name}: time = $time before ride to ${i2}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2}")
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

/**
 * A [Conveyor] demo variant of [TestConveyor2] in which parts request two cells and one part makes a
 * multi-hop ride, continuing on to a further access point after a delay rather than exiting where it
 * first stopped. Exercises riding across multiple segments with an intervening delay. Console-traced.
 */
class TestConveyor3(parent: ModelElement, conveyorType: Conveyor.Type) : ProcessModel(parent) {

    val conveyor: Conveyor
    val i1 = "A"
    val i2 = "B"
    val i3 = "C"

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
            println("${entity.name}: time = $time before access at ${i1}")
            var amt = 2
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
                println("${entity.name}: time = $time before ride to ${i2}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2}")
            } else if (entity.name == "Part4") {
                println("${entity.name}: time = $time before ride to ${i1}")
                rideConveyor(a, i1)
                println("${entity.name}: time = $time after ride to ${i1}")
            } else {
                println("${entity.name}: time = $time before ride to ${i2}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2}")
            }
            println("${entity.name}: The riding time was ${time - timeStamp}")
            delay(2.5)
//            delay(10.0)
//            println("${entity.name}: time = $time after second delay of 10.0 ")
            if (entity.name == "Part4") {
                println("${entity.name}: time = $time continue to ride to ${i2}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2}")
            }
            println("${entity.name}: time = $time before exit ")
            exitConveyor(a)
            println("${entity.name}: time = $time after exit ")
        }

    }

}

/**
 * A [Conveyor] demo variant of [TestConveyor3] in which a part delays while holding its conveyor
 * allocation before riding, highlighting how accumulating versus non-accumulating conveyors differ when
 * an entity blocks the line. Console-traced.
 */
class TestConveyor4(parent: ModelElement, conveyorType: Conveyor.Type) : ProcessModel(parent) {

    val conveyor: Conveyor
    val i1 = "A"
    val i2 = "B"
    val i3 = "C"

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
            println("${entity.name}: time = $time before access at ${i1}")
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
                println("${entity.name}: time = $time before ride to ${i2}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2}")
            } else if (entity.name == "Part4") {
                println("${entity.name}: time = $time before ride to ${i1}")
                rideConveyor(a, i1)
                println("${entity.name}: time = $time after ride to ${i1}")
            } else {
                println("${entity.name}: time = $time before ride to ${i2}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2}")
            }
            println("${entity.name}: The riding time was ${time - timeStamp}")
            delay(2.5)
//            delay(10.0)
//            println("${entity.name}: time = $time after second delay of 10.0 ")
            if (entity.name == "Part4") {
                println("${entity.name}: time = $time continue to ride to ${i2}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2}")
            }
            println("${entity.name}: time = $time before exit ")
            exitConveyor(a)
            println("${entity.name}: time = $time after exit ")
        }

    }

}

/**
 * A [Conveyor] demo variant that launches several parts at nearly the same time, each requesting two
 * cells and riding A to B together, to exercise contention and congestion on the conveyor.
 * Console-traced.
 */
class TestConveyor5(parent: ModelElement, conveyorType: Conveyor.Type) : ProcessModel(parent) {

    val conveyor: Conveyor
    val i1 = "A"
    val i2 = "B"
    val i3 = "C"

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
        for (i in 1..5){
            val p1 = Part("Part$i")
            activate(p1.conveyingProcess, timeUntilActivation = 0.1)
        }
    }

    private inner class Part(name: String? = null) : Entity(name) {
        val conveyingProcess: KSLProcess = process("test") {
            println("${entity.name}: time = $time before access at ${i1}")
            val amt = 2
            val a = requestConveyor(conveyor, i1, amt)
            println("${entity.name}: time = $time after access")
//            println("${entity.name}: time = $time before delay of 2.0")
//            delay(2.0)
//            println("${entity.name}: time = $time after delay of 2.0")
            println("${entity.name}: time = $time before ride to ${i2}")
            timeStamp = time
            rideConveyor(a, i2)
            println("${entity.name}: time = $time after ride to ${i2}")
            println("${entity.name}: The riding time was ${time - timeStamp}")
 //           delay(2.5)
//            delay(10.0)
//            println("${entity.name}: time = $time after second delay of 10.0 ")
            println("${entity.name}: time = $time before exit ")
            exitConveyor(a)
            println("${entity.name}: time = $time after exit ")
        }

    }

}