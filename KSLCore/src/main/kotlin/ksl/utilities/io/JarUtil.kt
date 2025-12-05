package ksl.utilities.io

import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile

fun main() {
   val jarName = "/Users/rossetti/Documents/GitHub/KSLTestModel/build/libs/KSLTestModel.jar"
    val jarPath = Paths.get(jarName)
    val names = JarUtil.classNamesInJarFile(jarPath)
    println("Class names:")
    for (name in names) {
        println(name)
    }
}

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
}