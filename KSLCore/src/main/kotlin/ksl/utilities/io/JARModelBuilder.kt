package ksl.utilities.io

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.io.DynamicJarClassLoader
import java.nio.file.Path
import java.nio.file.Paths

/**
 *
 */
class JARModelBuilder(
    jarPath: Path,
    modelBuilderClassName: String? = null
) : ModelBuilderIfc , AutoCloseable {

    private var myBuilder: ModelBuilderIfc? = null

    val isBuildable: Boolean
        get() = myBuilder != null

    init {
        val myLoader = DynamicJarClassLoader(jarPath)
        val modelBuilderClass : Class<*> = if (modelBuilderClassName != null) {
            require(modelBuilderClassName.isNotBlank()) { "ModelBuilder class name cannot be blank" }
            require(myLoader.classNames.contains(modelBuilderClassName)) { "ModelBuilder class name: $modelBuilderClassName is not in the JAR file. " }
            // directly load it
            val loadedClass = myLoader.loadClass(modelBuilderClassName)
            val superClass = ModelBuilderIfc::class.java
            require (superClass.isAssignableFrom(loadedClass) && loadedClass != superClass) {
                "The model builder class $modelBuilderClassName does not implement $superClass."
            }
            loadedClass
        } else {
            // Need to try to find a class that implements the ModelBuilderIfc within the JAR
            val modelBuilders = myLoader.findSubClasses(ModelBuilderIfc::class.java)
            require(modelBuilders.isNotEmpty()) { "No model builder class name was provided and the JAR did not contain at least one ModelBuilderIfc" }
            // assume that it is the first one
            val c : Class<*>  = modelBuilders.first()
            c
        }
        // first try to load it as a singleton that implements the interface
        var builder = myLoader.singletonObjectReference(modelBuilderClass.name)
        if (builder == null) {
            // if not a singleton then try to create it from a no argument constructor
            builder = myLoader.noArgumentInstance(modelBuilderClass)
        }
        require(builder != null) { "Unable to instantiate $modelBuilderClassName builder instance" }
        // if not a singleton then try to create it from a no argument constructor
        myBuilder = builder as ModelBuilderIfc
        myLoader.close()
    }

    /**
     * Constructor for a JAR file
     */
    constructor(jarPath: String) : this(Paths.get(jarPath))

    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?,
        defaultKSLDatabaseObserverOption: Boolean
    ): Model {
        require(myBuilder != null) {"The model builder is not available for building" }
        return myBuilder!!.build(modelConfiguration, experimentRunParameters, defaultKSLDatabaseObserverOption)
    }

    override fun close() {
        myBuilder = null
    }

}