package ksl.utilities.io

import ksl.simulation.ModelElement
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLClassLoader
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.kotlinFunction
import kotlin.sequences.forEach
import kotlin.reflect.KProperty0
import kotlin.use

val KProperty0<*>.isLazyInitialized: Boolean
    get() {
        // Prevent IllegalAccessException from JVM access check
        isAccessible = true
        return (getDelegate() as Lazy<*>).isInitialized()
    }

/**
 * Returns the value of the given lazy property if initialized, null
 * otherwise.
 */
val <T> KProperty0<T>.orNull: T?
    get() = if (isLazyInitialized) get() else null


/**
 * Utility for dynamically loading and instantiating classes from JAR files
 */
class DynamicJarClassLoader(val jarPaths: List<Path>) : AutoCloseable {

    /**
     *  The class names found within the JAR files
     */
    val classNames: Set<String>
    private val urlArray: Array<URL>
    private val classLoader: URLClassLoader by lazy {
        URLClassLoader(urlArray, this.javaClass.classLoader)
    }

    init {
        if (jarPaths.isEmpty()) {
            throw IllegalArgumentException("At least one JAR file path must be provided")
        }
        val urlList = mutableListOf<URL>()
        val cNames = mutableSetOf<String>()
        for (path in jarPaths) {
            val jarFile = path.toFile()
            if (!jarFile.exists()) {
                throw IllegalArgumentException("JAR file not found: $path")
            }
            val jar = JarFile(jarFile)
            cNames.addAll(classNamesInJarFile(jar))
            val url = jarFile.toURI().toURL()
            urlList.add(url)
        }
        classNames = cNames
        urlArray = urlList.toTypedArray()
    }

    /**
     * Constructor for a single JAR file
     */
    constructor(jarPath: Path) : this(listOf(jarPath))

    /**
     * Constructor for a single JAR file
     */
    constructor(jarPath: String) : this(listOf(Paths.get(jarPath)))

    /**
     * Constructor for multiple JAR files using vararg
     */
    constructor(vararg jarPaths: Path) : this(jarPaths.toList())

    /**
     * Constructor for multiple JAR files using vararg
     */
    constructor(vararg jarPaths: String) : this(jarPaths.map { Paths.get(it) })

    override fun toString(): String {
        val sb = StringBuilder().apply {
            appendLine("DynamicJarClassLoader:")
            appendLine("Paths to JAR Files:")
            val paths = getLoadedJarPaths()
            for (path in paths) {
                appendLine(path)
            }
            appendLine("Classes in JAR Files:")
            for (name in classNames) {
                appendLine(name)
            }
        }
        return sb.toString()
    }

    /**
     * Get the list of loaded JAR paths as a list of strings
     */
    fun getLoadedJarPaths(): List<String> {
        return jarPaths.map { it.toString() }
    }

    /**
     * Load a class by its fully qualified name
     */
    fun loadClass(className: String): Class<*> {
        require(classNames.contains(className)) { "The class named $className is not a class than can be loaded." }
        return try {
            classLoader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            throw IllegalArgumentException("Class not found: $className", e)
        }
    }

    fun findSubClasses(superClass: Class<*>): List<Class<*>> {
        val subclasses = mutableListOf<Class<*>>()
        for (name in classNames) {
            val loadedClass = classLoader.loadClass(name)
            // Check if it is a subclass (assignable from) the target class
            if (superClass.isAssignableFrom(loadedClass) && loadedClass != superClass) {
                subclasses.add(loadedClass)
            }
        }
        return subclasses
    }

    fun publicConstructors(loadedClass: Class<*>): List<KFunction<*>> {
        if (!classNames.contains(loadedClass.name)) {
            return emptyList()
        }
        val constructors = mutableListOf<KFunction<*>>()
        for (constructor in loadedClass.constructors) {
            val cf = constructor.kotlinFunction
            if (cf != null) {
                constructors.add(cf)
            }
        }
        return constructors
    }

    fun publicConstructors(className: String): List<KFunction<*>> {
        if (!classNames.contains(className)) {
            return emptyList()
        }
        val loadedClass = classLoader.loadClass(className)
        return publicConstructors(loadedClass)
    }

    fun declaredPublicStaticFunctions(className: String): List<KFunction<*>> {
        if (!classNames.contains(className)) {
            return emptyList()
        }
        val loadedClass = classLoader.loadClass(className)
        return declaredPublicStaticFunctions(loadedClass)
    }

    fun declaredPublicStaticFunctions(loadedClass: Class<*>): List<KFunction<*>> {
        if (!classNames.contains(loadedClass.name)) {
            return emptyList()
        }
        val functions = mutableListOf<KFunction<*>>()
        for (method in loadedClass.declaredMethods) {
            //       println("method: $method")
            val modifiers = method.modifiers
            if (Modifier.isStatic(modifiers)) {
                val cf = method.kotlinFunction
                if ((cf != null)) {
                    functions.add(cf)
                }
            }
        }
        return functions
    }

    fun declaredPublicStaticFunction(className: String, functionName: String): KFunction<*>? {
        if (!classNames.contains(className)) {
            return null
        }
        val loadedClass = classLoader.loadClass(className)
        return declaredPublicStaticFunction(loadedClass, functionName)
    }

    fun declaredPublicStaticFunction(loadedClass: Class<*>, functionName: String): KFunction<*>? {
        val functions = declaredPublicStaticFunctions(loadedClass)
        if (functions.isEmpty()) {
            return null
        }
        return functions.find { it.name == functionName }
    }

    fun declaredPublicNonStaticFunctions(className: String): List<KFunction<*>> {
        if (!classNames.contains(className)) {
            return emptyList()
        }
        val loadedClass = classLoader.loadClass(className)
        return declaredPublicNonStaticFunctions(loadedClass)
    }

    fun declaredPublicNonStaticFunctions(loadedClass: Class<*>): List<KFunction<*>> {
        if (!classNames.contains(loadedClass.name)) {
            return emptyList()
        }
        val functions = mutableListOf<KFunction<*>>()
        for (method in loadedClass.declaredMethods) {
            val modifiers = method.modifiers
            if (Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers)) {
                val cf = method.kotlinFunction
                if (cf != null) {
                    functions.add(cf)
                }
            }
        }
        return functions
    }

    fun declaredPublicNonStaticFunction(className: String, functionName: String): KFunction<*>? {
        if (!classNames.contains(className)) {
            return null
        }
        val loadedClass = classLoader.loadClass(className)
        return declaredPublicNonStaticFunction(loadedClass, functionName)
    }

    fun declaredPublicNonStaticFunction(loadedClass: Class<*>, functionName: String): KFunction<*>? {
        val functions = declaredPublicNonStaticFunctions(loadedClass)
        if (functions.isEmpty()) {
            return null
        }
        return functions.find { it.name == functionName }
    }

    /**
     *  A Kotlin object declaration defines a (static) singleton object with the underlying synthetic
     *  class defined with a class name the same as the name of the object. This function returns
     *  the associated object reference as an Any reference or null.
     *  
     *  @param singletonName the name of the object that was declared by object definition
     *  @return the reference to the object as an Any, null if not available
     */
    fun singletonObjectReference(singletonName: String) : Any? {
        if (!classNames.contains(singletonName)) {
            return null
        }
        val loadedClass = classLoader.loadClass(singletonName)
        try {
            val staticInstance = loadedClass.getDeclaredField("INSTANCE")
            return staticInstance.get(null)
        } catch (e: NoSuchFieldException) {
            return null
        }
    }

//    /**
//     * Load a class and return it as a Kotlin KClass for reflection
//     */
//    fun loadKClass(className: String): KClass<*> {
//        return loadClass(className).kotlin
//    }

//    /**
//     * Create an instance using the no-argument constructor
//     */
//    fun <T : Any> createInstance(className: String): T {
//        val kClass = loadKClass(className)
//        val noArgConstructor = kClass.constructors.find { it.parameters.isEmpty() }
//            ?: throw IllegalArgumentException("No no-arg constructor found for $className")
//
//        @Suppress("UNCHECKED_CAST")
//        return noArgConstructor.call() as T
//    }
//
//    /**
//     * Create an instance using a constructor with parameters
//     */
//    fun <T : Any> createInstance(className: String, vararg args: Any?): T {
//        val kClass = loadKClass(className)
//        val constructor = findMatchingConstructor(kClass, args)
//            ?: throw IllegalArgumentException(
//                "No matching constructor found for $className with ${args.size} arguments"
//            )
//
//        @Suppress("UNCHECKED_CAST")
//        return constructor.call(*args) as T
//    }
//
//    /**
//     * Find a constructor that matches the provided arguments
//     */
//    private fun findMatchingConstructor(kClass: KClass<*>, args: Array<out Any?>): KFunction<Any>? {
//        return kClass.constructors.find { constructor ->
//            val params = constructor.parameters
//            if (params.size != args.size) return@find false
//
//            params.zip(args).all { (param, arg) ->
//                arg == null || param.type.classifier?.let { classifier ->
//                    (classifier as? KClass<*>)?.javaObjectType?.isInstance(arg) ?: false
//                } ?: false
//            }
//        }
//    }
//
//    /**
//     * Get all constructors for a class
//     */
//    fun getConstructors(className: String): List<KFunction<Any>> {
//        val kClass = loadKClass(className)
//        return kClass.constructors.toList()
//    }
//
//    /**
//     * Get information about a constructor
//     */
//    fun getConstructorInfo(constructor: KFunction<*>): String {
//        val params = constructor.parameters.joinToString(", ") { param ->
//            "${param.name}: ${param.type}"
//        }
//        return "Constructor($params)"
//    }
//
//    /**
//     * Get all public functions of a loaded class
//     */
//    fun getFunctions(className: String): List<KFunction<*>> {
//        val kClass = loadKClass(className)
//        return kClass.functions.filter { it.visibility == kotlin.reflect.KVisibility.PUBLIC }
//    }
//
//    /**
//     * Get all properties of a loaded class
//     */
//    fun getProperties(className: String): List<String> {
//        val kClass = loadKClass(className)
//        return kClass.memberProperties.map { "${it.name}: ${it.returnType}" }
//    }

//    /**
//     * Call a function on an instance
//     */
//    fun callFunction(instance: Any, functionName: String, vararg args: Any?): Any? {
//        val kClass = instance::class
//        val function = kClass.functions.find { it.name == functionName }
//            ?: throw IllegalArgumentException("Function $functionName not found")
//
//        return function.call(instance, *args)
//    }

    /**
     * Close the class loader
     */
    override fun close() {
        if (!(::classLoader.isLazyInitialized)) {
            return
        }
        classLoader.close()
    }

    companion object {

        /**
         * Extracts the class names contained within the JAR file.
         *
         * @param jarFilePath the path to the JAR File
         * @param includeInnerClasses a flag (default true) that indicates if inner classes
         * are to be included in the returned set
         * @return the set of class names
         */
        fun classNamesInJarFile(jarFilePath: Path, includeInnerClasses: Boolean = true): Set<String> {
            try {
                val file = jarFilePath.toFile()
                val jarFile = JarFile(file)
                val names = classNamesInJarFile(jarFile, includeInnerClasses)
                jarFile.close()
                return names
            } catch (e: Exception) {
                return emptySet()
            }
        }

        /**
         * Extracts the class names contained within the JAR file. The supplied JAR file
         * is not closed by the function.
         *
         * @param jar the JAR file to process
         * @param includeInnerClasses a flag (default true) that indicates if inner classes
         * are to be included in the returned set
         * @return the set of class names
         */
        fun classNamesInJarFile(jar: JarFile, includeInnerClasses: Boolean = true): Set<String> {
            val entries = jar.entries()
            val classNames = mutableSetOf<String>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.endsWith(".class") &&
                    !entry.name.startsWith("META-INF/") && !entry.isDirectory
                ) {
                    val className = entry.name
                        .removeSuffix(".class")
                        .replace('/', '.')
                        .replace('\\', '.') // Handle both Unix and Windows path separators

                    if (includeInnerClasses || !className.contains('$')) {
                        classNames.add(className)
                    }
                }
            }
            return classNames
        }

        /**
         * Lists all classes and subclasses in a JAR file for a given parent class using Kotlin reflection
         *
         * @param jarFilePath Path to the JAR file
         * @param parentClass The parent KClass to search for subclasses
         * @return Set of class names that extend or implement the parent class
         */
        fun findSubclasses(jarFilePath: Path, parentClass: Class<*>): List<String> {
            val jarFile = jarFilePath.toFile()
            if (!jarFile.exists()) {
                return emptyList()
            }
            val subclasses = mutableListOf<String>()
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
                                // Load the class
                                val loadedClass = classLoader.loadClass(className)
                                if (parentClass.isAssignableFrom(loadedClass) && loadedClass != parentClass) {
                                    subclasses.add(loadedClass.name)
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
        fun findSubclassObjects(jarFilePath: Path, parentClass: Class<*>): List<Class<*>> {
            val jarFile = jarFilePath.toFile()
            if (!jarFile.exists()) {
                return emptyList()
            }
            val subclasses = mutableListOf<Class<*>>()
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
                                val loadedClass = classLoader.loadClass(className)
                                if (parentClass.isAssignableFrom(loadedClass) && loadedClass != parentClass) {
                                    subclasses.add(loadedClass)
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
}

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
        for (subClass in subClasses) {
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

//
///**
// * Example usage
// */
//fun testExamples() {
//    // Example 1: Single JAR file
//    println("=== Example 1: Single JAR File ===")
//    try {
//        DynamicJarClassLoader("example.jar").use { loader ->
//            val instance: Any = loader.createInstance("com.example.MyClass")
//            println("Created instance: $instance")
//
//            val constructors = loader.getConstructors("com.example.MyClass")
//            println("\nAvailable constructors:")
//            constructors.forEach { println("  ${loader.getConstructorInfo(it)}") }
//        }
//    } catch (e: IllegalArgumentException) {
//        println("Configuration error: ${e.message}")
//    } catch (e: ClassNotFoundException) {
//        println("Class not found in JAR: ${e.message}")
//    } catch (e: NoClassDefFoundError) {
//        println("Missing dependency class: ${e.message}")
//    } catch (e: SecurityException) {
//        println("Security restriction: ${e.message}")
//    } catch (e: Exception) {
//        println("Unexpected error: ${e.message}")
//    }
//
//    // Example 2: Multiple JAR files using List
//    println("\n=== Example 2: Multiple JAR Files (List) ===")
//    try {
//        val jarFileStrings = listOf("library1.jar", "library2.jar", "app.jar")
//        val jarFiles = jarFileStrings.map { Paths.get(it) }
//        DynamicJarClassLoader(jarFiles).use { loader ->
//            // Can load classes from any of the JARs
//            val instance1: Any = loader.createInstance("com.library1.Utility")
//            val instance2: Any = loader.createInstance("com.library2.Helper")
//            val instance3: Any = loader.createInstance("com.app.MainClass")
//
//            println("Loaded classes from multiple JARs:")
//            println("  Instance 1: $instance1")
//            println("  Instance 2: $instance2")
//            println("  Instance 3: $instance3")
//        }
//    } catch (e: IllegalArgumentException) {
//        println("Configuration error: ${e.message}")
//        println("Check that all JAR file paths are valid")
//    } catch (e: ClassNotFoundException) {
//        println("Class not found: ${e.message}")
//        println("Verify the class name and that it exists in one of the JARs")
//    } catch (e: NoClassDefFoundError) {
//        println("Dependency missing: ${e.message}")
//        println("Ensure all required dependency JARs are included")
//    } catch (e: InstantiationException) {
//        println("Cannot instantiate: ${e.message}")
//        println("The class may be abstract or an interface")
//    } catch (e: IllegalAccessException) {
//        println("Access denied: ${e.message}")
//        println("The constructor may be private or protected")
//    } catch (e: SecurityException) {
//        println("Security restriction: ${e.message}")
//    } catch (e: Exception) {
//        println("Unexpected error: ${e.message}")
//        e.printStackTrace()
//    }
//
//    // Example 3: Multiple JAR files using vararg
//    println("\n=== Example 3: Multiple JAR Files (Vararg) ===")
//    try {
//        DynamicJarClassLoader("core.jar", "plugins.jar", "extensions.jar").use { loader ->
//            val instance: Any = loader.createInstance(
//                "com.example.Person",
//                "John Doe",
//                30
//            )
//            println("Created instance: $instance")
//
//            val result = loader.callFunction(instance, "greet")
//            println("Function result: $result")
//        }
//    } catch (e: IllegalArgumentException) {
//        println("Invalid argument: ${e.message}")
//    } catch (e: ClassNotFoundException) {
//        println("Class not found: ${e.message}")
//    } catch (e: NoSuchMethodException) {
//        println("Method not found: ${e.message}")
//        println("Check that the method name and parameters are correct")
//    } catch (e: InvocationTargetException) {
//        println("Method threw an exception: ${e.targetException.message}")
//        e.targetException.printStackTrace()
//    } catch (e: NoClassDefFoundError) {
//        println("Missing dependency: ${e.message}")
//    } catch (e: Exception) {
//        println("Unexpected error: ${e.message}")
//    }
//
//    // Example 5: Loading classes with dependencies across JARs
//    println("\n=== Example 5: Classes with Cross-JAR Dependencies ===")
//    try {
//        // Load both the main JAR and its dependency JAR
//        DynamicJarClassLoader("dependency.jar", "main.jar").use { loader ->
//            // MainClass from main.jar uses classes from dependency.jar
//            val instance: Any = loader.createInstance("com.main.MainClass")
//            println("Created instance with cross-JAR dependencies: $instance")
//
//            val result = loader.callFunction(instance, "processWithDependency")
//            println("Result: $result")
//        }
//    } catch (e: IllegalArgumentException) {
//        println("Configuration error: ${e.message}")
//        println("Ensure all JAR files are specified and exist")
//    } catch (e: ClassNotFoundException) {
//        println("Class not found: ${e.message}")
//        println("The class may not exist in any of the loaded JARs")
//    } catch (e: NoClassDefFoundError) {
//        println("Dependency resolution failed: ${e.message}")
//        println("A required dependency class is missing. Common causes:")
//        println("  - Dependency JAR not included in the loader")
//        println("  - Wrong order of JARs (load dependencies first)")
//        println("  - Version mismatch between JARs")
//    } catch (e: LinkageError) {
//        println("Class linking error: ${e.message}")
//        println("This may indicate incompatible class versions or duplicate classes")
//    } catch (e: InvocationTargetException) {
//        println("Method execution failed: ${e.targetException.message}")
//        println("The called method threw an exception:")
//        e.targetException.printStackTrace()
//    } catch (e: SecurityException) {
//        println("Security restriction: ${e.message}")
//    } catch (e: Exception) {
//        println("Unexpected error: ${e.message}")
//        e.printStackTrace()
//    }
//}
