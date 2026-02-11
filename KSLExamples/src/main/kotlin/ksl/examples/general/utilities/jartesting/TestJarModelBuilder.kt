package ksl.examples.general.utilities.jartesting

import ksl.utilities.io.JARModelBuilder

fun main() {
    val jarPath =
        "/Users/rossetti/Library/CloudStorage/OneDrive-UniversityofArkansas/MyDocuments/old code/KSLTestModel/build/libs/KSLTestModel.jar"

//    val jarPath = "build/libs/KSLTestModel.jar"
    //val mb = JARModelBuilder(jarPath, "work.STEMFairScheduledCase")
    val mb = JARModelBuilder(jarPath)
    println(mb)
    val model = mb.build()
    //println(model)
    model.simulate()
    model.print()
    mb.close()
}