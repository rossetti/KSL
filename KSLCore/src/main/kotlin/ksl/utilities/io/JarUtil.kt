package ksl.utilities.io

import ksl.simulation.Model
import ksl.simulation.ModelElement
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import java.io.IOException
import java.net.URLClassLoader
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.jvm.jvmName

fun main() {
  // val jarName = "/Users/rossetti/Documents/GitHub/KSLTestModel/build/libs/KSLTestModel.jar"
    val jarName = "/Users/rossetti/Library/CloudStorage/OneDrive-UniversityofArkansas/MyDocuments/old code/KSLTestModel/build/libs/KSLTestModel.jar"
    val jarPath = Paths.get(jarName)
    val names = JarUtil.classNamesInJarFile(jarPath)
    println("Class names:")
    for (name in names) {
        println(name)
    }
    println()
    val names2 = JarUtil.findSubclasses(jarPath, ModelElement::class)
    println("Class names:")
    for (name in names2) {
        println(name)
    }
}

/**
 * Data class to hold class metadata from Kotlin reflection
 */
data class ClassInfo(
    val name: String,
    val qualifiedName: String,
    val isAbstract: Boolean,
    val isData: Boolean,
    val isSealed: Boolean,
    val isInner: Boolean,
    val isCompanion: Boolean
)

object JarUtil {

    fun classNamesInJarFile(jarFilePath: Path): Set<String> {
        try {
            val file = jarFilePath.toFile()
            val jarFile = JarFile(file)
            val names = classNamesInJarFile(jarFile)
            jarFile.close()
            return names
        } catch (e: Exception) {
            return emptySet()
        }
    }

    fun classNamesInJarFile(jarFile: JarFile): Set<String> {
        val entries = jarFile.entries().iterator()
        val names = mutableSetOf<String>()
        while (entries.hasNext()) {
            val entry = entries.next()
            val entryName = entry.name
            if (entryName.endsWith(".class") && !entry.isDirectory) {
                // Convert the path to a fully qualified class name
                val className = entryName
                    .removeSuffix(".class")
                    .replace('/', '.')
                    .replace('\\', '.') // Handle both Unix and Windows path separators
                names.add(className)
            }
        }
        return names
    }

    /**
     * Lists all classes and subclasses in a JAR file for a given parent class using Kotlin reflection
     *
     * @param jarFilePath Path to the JAR file
     * @param parentClass The parent KClass to search for subclasses
     * @return Set of class names that extend or implement the parent class
     */
    fun findSubclasses(jarFilePath: Path, parentClass: KClass<*>): Set<String> {
        val jarFile = jarFilePath.toFile()
        if (!jarFile.exists()) {
            return emptySet()
        }
        val subclasses = mutableSetOf<String>()
        // Create a URLClassLoader to load classes from the JAR
        val jarUrl = jarFile.toURI().toURL()
        URLClassLoader(
            arrayOf(jarUrl),
            Thread.currentThread().contextClassLoader
        ).use { classLoader ->

            JarFile(jarFile).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .forEach { entry ->
                        // Convert file path to class name
                        val className = entry.name
                            .replace('/', '.')
                            .removeSuffix(".class")

                        try {
                            // Load the class and convert to KClass
                            val javaClass = classLoader.loadClass(className)
                            val kClass = javaClass.kotlin

                            // Check if it's a subclass of the parent using Kotlin reflection
                            if (kClass != parentClass && kClass.isSubclassOf(parentClass)) {
                                subclasses.add(className)
                            }

                        } catch (e: Exception) {
                            // Skip classes that can't be loaded or reflected
                        }
                    }
            }
        }

        return subclasses
    }

    /**
     * Alternative method that returns KClass objects instead of class names
     */
    fun findSubclassObjects(jarFilePath: Path, parentClass: KClass<*>): Set<KClass<*>> {
        val jarFile = jarFilePath.toFile()
        if (!jarFile.exists()) {
            return emptySet()
        }
        val subclasses = mutableSetOf<KClass<*>>()
        val jarUrl = jarFile.toURI().toURL()
        URLClassLoader(
            arrayOf(jarUrl),
            Thread.currentThread().contextClassLoader
        ).use { classLoader ->

            JarFile(jarFile).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .forEach { entry ->
                        val className = entry.name
                            .replace('/', '.')
                            .removeSuffix(".class")

                        try {
                            val javaClass = classLoader.loadClass(className)
                            val kClass = javaClass.kotlin

                            if (kClass != parentClass && kClass.isSubclassOf(parentClass)) {
                                subclasses.add(kClass)
                            }

                        } catch (e: Exception) {
                            // Skip classes that can't be loaded or reflected
                        }
                    }
            }
        }

        return subclasses
    }

    /**
     * Find subclasses with additional metadata using Kotlin reflection
     */
    fun findSubclassesWithMetadata(jarFilePath: Path, parentClass: KClass<*>): Set<ClassInfo> {
        val jarFile = jarFilePath.toFile()
        if (!jarFile.exists()) {
            return emptySet()
        }
        val subclasses = mutableSetOf<ClassInfo>()
        val jarUrl = jarFile.toURI().toURL()
        URLClassLoader(
            arrayOf(jarUrl),
            Thread.currentThread().contextClassLoader
        ).use { classLoader ->

            JarFile(jarFile).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .forEach { entry ->
                        val className = entry.name
                            .replace('/', '.')
                            .removeSuffix(".class")

                        try {
                            val javaClass = classLoader.loadClass(className)
                            val kClass = javaClass.kotlin

                            if (kClass != parentClass && kClass.isSubclassOf(parentClass)) {
                                subclasses.add(ClassInfo(
                                    name = className,
                                    qualifiedName = kClass.qualifiedName ?: className,
                                    isAbstract = kClass.isAbstract,
                                    isData = kClass.isData,
                                    isSealed = kClass.isSealed,
                                    isInner = kClass.isInner,
                                    isCompanion = kClass.isCompanion
                                ))
                            }

                        } catch (e: Exception) {
                            // Skip classes that can't be loaded or reflected
                        }
                    }
            }
        }

        return subclasses
    }
}