package ksl.examples.general.utilities.jartesting

import ksl.simulation.ModelElement
import ksl.utilities.io.DynamicJarClassLoader
import java.nio.file.Paths
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.staticFunctions
import kotlin.reflect.jvm.kotlinFunction
import kotlin.use

fun main() {
    val jarName =
        "/Users/rossetti/Library/CloudStorage/OneDrive-UniversityofArkansas/MyDocuments/old code/KSLTestModel/build/libs/KSLTestModel.jar"
    test1(jarName)
    //   test2(jarName, "work.Ch7Example7Kt")
    test2(jarName, "work.StemFairMixerEnhanced")
    //test3(jarName, "work.Ch7Example7Kt", "main")

}

fun test0() {
    //    // val jarName = "/Users/rossetti/Documents/GitHub/KSLTestModel/build/libs/KSLTestModel.jar"
    val jarName =
        "/Users/rossetti/Library/CloudStorage/OneDrive-UniversityofArkansas/MyDocuments/old code/KSLTestModel/build/libs/KSLTestModel.jar"
//    test1(jarName)
    // test2(jarName)

    println()
//    println("Try DynamicJarClassLoader")
//    val jar2 = "build/libs/KSLTestModel-all.jar"
    //   val loader = DynamicJarClassLoader(jar2)
    //   KSL.out.println(loader)

    println()
    println("Try DynamicJarClassLoader")
    val loader = DynamicJarClassLoader(jarName)
    println(loader)
    println()
    val clazz = loader.loadClass("work.Ch7Example7Kt")
    println("Constructors:")
    clazz.constructors.forEach {
        println(it)
    }
    println("Methods")
    clazz.methods.forEach { println(it) }
    // klass.members.forEach {println(it)}
    println("Declared Methods")
    //clazz.declaredMethods.forEach { println(it) }
    for (method in clazz.declaredMethods) {
        println("java: $method")
        val kFunction = method.kotlinFunction
        println("kFunction: ${kFunction?.name}")
//        if (kFunction != null && kFunction.name == "main") {
//            kFunction.call()
//        }
    }
    val klass = clazz.kotlin
    println("Declared Functions:")
    klass.declaredFunctions.forEach { println(it) }
    println("Static Functions:")
    klass.staticFunctions.forEach { println(it) }
    //  klass.declaredMemberFunctions.forEach { println(it) }

    //   KSL.out.println(loader)
}

fun test1(jarName: String) {
    println("=== Test 1 ===")
    try {
        val jarPath = Paths.get(jarName)
        val loader = DynamicJarClassLoader(jarPath)
        println("Class names:")
        for (name in loader.classNames) {
            println(name)
        }
        println()
        val subClasses = loader.findSubClasses(ModelElement::class.java)
        println("Subclasses of : ksl.simulation.ModelElement")
        for ((name, subClass) in subClasses) {
            println(subClass.name)
        }
        loader.close()
    } catch (e: IllegalArgumentException) {
        println("Configuration error: ${e.message}")
    } catch (e: ClassNotFoundException) {
        println("Class not found in JAR: ${e.message}")
    } catch (e: NoClassDefFoundError) {
        println("Missing dependency class: ${e.message}")
    } catch (e: SecurityException) {
        println("Security restriction: ${e.message}")
    } catch (e: Exception) {
        println("Unexpected error: ${e.message}")
    }
}

fun test2(jarName: String, className: String) {
    println("=== Test 2 ===")
    try {
        DynamicJarClassLoader(jarName).use { loader ->
            val loadedClass = loader.loadClass(className)
            println("Loaded class:${loadedClass.name}")
            println("Constructors:")
            val constructors = loader.publicConstructors(loadedClass)
            constructors.forEach { println(it) }
            println()
            println("Static Methods:")
            val staticMethods = loader.declaredPublicStaticFunctions(loadedClass)
            staticMethods.forEach { println(it) }
            println()
            println("Non-Static Methods")
            val nonStaticMethods = loader.declaredPublicNonStaticFunctions(loadedClass)
            nonStaticMethods.forEach { println(it) }
        }
    } catch (e: IllegalArgumentException) {
        println("Configuration error: ${e.message}")
    } catch (e: ClassNotFoundException) {
        println("Class not found in JAR: ${e.message}")
    } catch (e: NoClassDefFoundError) {
        println("Missing dependency class: ${e.message}")
    } catch (e: SecurityException) {
        println("Security restriction: ${e.message}")
    } catch (e: Exception) {
        println("Unexpected error: ${e.message}")
    }
}

fun test3(jarName: String, className: String, methodName: String) {
    println("=== Test 3 ===")
    try {
        DynamicJarClassLoader(jarName).use { loader ->
            val loadedClass = loader.loadClass(className)
            println("Loaded class:${loadedClass.name}")
            println("Running function: $methodName:")
            val function = loader.declaredPublicStaticFunction(loadedClass, methodName)
            function?.call()
        }
    } catch (e: IllegalArgumentException) {
        println("Configuration error: ${e.message}")
    } catch (e: ClassNotFoundException) {
        println("Class not found in JAR: ${e.message}")
    } catch (e: NoClassDefFoundError) {
        println("Missing dependency class: ${e.message}")
    } catch (e: SecurityException) {
        println("Security restriction: ${e.message}")
    } catch (e: Exception) {
        println("Unexpected error: ${e.message}")
    }
}