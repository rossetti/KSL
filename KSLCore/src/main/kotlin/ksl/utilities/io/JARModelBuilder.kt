package ksl.utilities.io

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths

/**
 *  Using the supplied JAR file, this class creates an instance of an object that implements
 *  the ModelBuilderIfc interface. The resulting instance can be used to build models as
 *  defined by the JAR file classes.
 *
 *  It is much more efficient to supply a class name found within the JAR file that implements the
 *  ModelBuilderIfc. If not supplied, the JAR will be searched for the first class that implements the
 *  ModelBuilderIfc interface. If no classes are found that implement the interface, then an exception occurs.
 *
 *  The simplest approach is to include a Kotlin object reference declaration that implements the
 *  ModelBuilderIfc interface in the compiled code within the JAR file. Then, you supply the name of
 *  the object/class as the [modelBuilderClassName] parameter.  If you don't supply an object reference
 *  declaration, then there must be one class within the JAR file that implements the ModelBuilderIfc
 *  interface, and it must have a public zero argument constructor.
 *
 *  If the required KSLCore release's jar file is on the classpath for the implementation that uses
 *  the JARModelBuilder, then the jar file does not need to be a "fat" jar.  As long as the jar file
 *  has all the necessary user-defined class definitions needed to construct an instance of the desired
 *  model, then the necessary KSL classes will be loaded via the class loader's parent through
 *  delegation. The parent loader will be the loader required to instantiate this class.
 *
 *  The JARModelBuilder should not be closed until all required models are built. Once the builder
 *  is closed, no additional models can be built.  It is important to close the builder once
 *  all models have been built.
 *
 *  Because of the underlying JAR files and class loading, it is important to not store long-lasting
 *  references to the models built from the builder. This could have important memory issues because
 *  of how java class loading operates. See this [article](https://java.jiderhamn.se/2012/01/01/classloader-leaks-ii-find-and-work-around-unwanted-references/).
 *  for more information.
 *
 * @param jarPath the path to the JAR file. The file must be a JAR file and contain a class that implements
 * the ModelBuilderIfc interface.
 * @param modelBuilderClassName the (optional) name of the class within the JAR file that implements the ModelBuilderIfc
 */
class JARModelBuilder(
    private val loader: DynamicJarClassLoader,
    modelBuilderClassName: String? = null
) : ModelBuilderIfc, AutoCloseable {

    /**
     * Constructor for a JAR file
     */
    constructor(jarPath: String, modelBuilderClassName: String? = null)
            : this(Paths.get(jarPath), modelBuilderClassName)

    /**
     * Constructor for a JAR file
     */
    constructor(jarPath: Path, modelBuilderClassName: String? = null)
            : this(DynamicJarClassLoader(jarPath), modelBuilderClassName)

    private val modelBuildingClasses: Map<String, Class<*>> = loader.findSubClasses(ModelBuilderIfc::class.java)

    init {
        //validate loader by checking that it has at least one model building class
        require(modelBuildingClasses.isNotEmpty()) { "No classes that implement the ModelBuilderIfc interface were found in the loader." }
    }

    /**
     *  Assume if the loader was validated that it is open. It should remain open until closed
     *  via the close() function.
     */
    private var myLoaderOpenFlag: Boolean = true

    /**
     *  The class names in the loader that implement the ModelBuilderIfc interface
     */
    @Suppress("unused")
    val modelBuilderClassNames: List<String> = modelBuildingClasses.keys.toList()

    /**
     *  Note that this reference is instantiated by a custom class loader related to the JAR file
     */
    private var myBuilder: ModelBuilderIfc? = null

    var builderClassName: String? = null
        private set

    init {
        // if provided validate set up the model builder using the class name
        if (modelBuilderClassName != null) {
            initializeBuilder(modelBuilderClassName)
        }
    }

    /**
     *  All class names within the loader
     */
    val classNames: Set<String>
        get() = loader.classNames

    /**
     *  The list of URL representations for the JAR files
     */
    val jarURL: URL
        get() = loader.urlList.first()

    val jarPath: Path
        get() = loader.jarPaths.first()

    /**
     *  Initializes the builder so that model building can occur.  This function must be called
     *  before the first time build() is called.
     *
     *  @param modelBuilderClassName the fully qualified name within the JAR file that implements the ModelBuilderIfc
     *  interface and will serve as the builder.
     */
    fun initializeBuilder(modelBuilderClassName: String) {
        require(modelBuilderClassName.isNotBlank()) { "The supplied model building class name cannot be blank" }
        require(modelBuildingClasses.contains(modelBuilderClassName)) { "The supplied model builder class name $modelBuilderClassName is not present in the loader." }
        // the Class must now represent a valid class that implements the ModelBuilderIfc interface
        val modelBuilderClass = modelBuildingClasses[modelBuilderClassName]!!
        // first try to load it as a singleton object that implements the interface
        var builder = loader.singletonObjectReference(modelBuilderClass)
        if (builder == null) {
            // if not a singleton then try to create it from a no argument constructor
            builder = loader.noArgumentInstance(modelBuilderClass)
        }
        require(builder != null) { "Unable to instantiate a ModelBuilderIfc instance for the JAR: $jarPath" }
        // cast it to the required interface
        myBuilder = builder as ModelBuilderIfc
        builderClassName = modelBuilderClassName
    }

    /**
     *  The returned model will have been instantiated by the underlying class loader.
     *  Be sure not to store long-lasting references to the model instances because
     *  this may have memory implications that prevent garbage collection of the loader
     *  and any classes that it loaded.
     */
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?,
        defaultKSLDatabaseObserverOption: Boolean
    ): Model {
        require(myLoaderOpenFlag) { "The JARModelBuilder has been closed. Cannot build more models." }
        if (myBuilder == null) {
            initializeBuilder(modelBuilderClassNames.first())
        }
        return myBuilder!!.build(modelConfiguration, experimentRunParameters, defaultKSLDatabaseObserverOption)
    }

    override fun toString(): String {
        val sb = StringBuilder().apply {
            appendLine("JARModelBuilder:")
            appendLine("Builder class name: $builderClassName")
            appendLine("Building status: $myLoaderOpenFlag")
            appendLine("JAR URL: $jarURL")
            appendLine("JAR Path: $jarPath")
            appendLine()
            appendLine("Classes in JAR Files:")
            for (name in classNames) {
                appendLine(name)
            }
        }
        return sb.toString()
    }

    /**
     * Closes the underlying class loader. Once the loading mechanism is closed,
     * no additional model building is permitted.
     */
    override fun close() {
        myLoaderOpenFlag = false
        loader.close()
    }
}

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