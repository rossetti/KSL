package ksl.utilities.io

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Path
import java.nio.file.Paths

/**
 *  Using the supplied JAR file, this class creates an instance of an object that implements
 *  the ModelBuilderIfc interface. The resulting instance can be used to build models as
 *  defined by the JAR file classes.
 *
 *  It is much more efficient to supply a class name found within the JAR file that implements the
 *  ModelBuilderIfc. If not supplied, the JAR will be searched for the first class that implements the
 *  interface. If no classes are found that implement the interface, then an exception occurs.
 *
 *  Because of the underlying JAR files and class loading, it is important to close objects after using
 *  this class for model building.  Once closed it can no longer be used for building models.
 *
 * @param jarPath the path to the JAR file. The file must be a JAR file and contain a class that implements
 * the ModelBuilderIfc interface.
 * @param modelBuilderClassName the (optional) name of the class within the JAR file that implements the ModelBuilderIfc
 */
class JARModelBuilder(
    jarPath: Path,
    modelBuilderClassName: String? = null
) : ModelBuilderIfc, AutoCloseable {

    private var myBuilder: ModelBuilderIfc? = null

    val isBuildable: Boolean
        get() = myBuilder != null

    init {
        val myLoader = DynamicJarClassLoader(jarPath)
        val modelBuilderClass: Class<*> = if (modelBuilderClassName != null) {
            require(modelBuilderClassName.isNotBlank()) { "The supplied model building class name cannot be blank" }
            require(myLoader.classNames.contains(modelBuilderClassName)) { "ModelBuilder class name: $modelBuilderClassName is not in the JAR file : $jarPath" }
            // directly load it
            val loadedClass = myLoader.loadClass(modelBuilderClassName)
            val superClass = ModelBuilderIfc::class.java
            require(superClass.isAssignableFrom(loadedClass) && loadedClass != superClass) {
                "The supplied model builder class $modelBuilderClassName does not implement the ModelBuilderIfc interface."
            }
            loadedClass
        } else {
            // Need to try to find a class that implements the ModelBuilderIfc within the JAR
            val modelBuilders = myLoader.findSubClasses(ModelBuilderIfc::class.java)
            require(modelBuilders.isNotEmpty()) { "No model builder class name was provided and the JAR did not contain at least one ModelBuilderIfc, JAR file : $jarPath" }
            // assume that it is the first one
            val c: Class<*> = modelBuilders.first()
            c
        }
        // the Class must now represent a valid class that implements the ModelBuilderIfc interface
        // first try to load it as a singleton object that implements the interface
        var builder = myLoader.singletonObjectReference(modelBuilderClass.name)
        if (builder == null) {
            // if not a singleton then try to create it from a no argument constructor
            builder = myLoader.noArgumentInstance(modelBuilderClass)
        }
        require(builder != null) { "Unable to instantiate a ModelBuilderIfc instance for the JAR: $jarPath" }
        // if not a singleton then try to create it from a no argument constructor
        myBuilder = builder as ModelBuilderIfc
        myLoader.close()
    }

    /**
     * Constructor for a JAR file
     */
    constructor(jarPath: String) : this(Paths.get(jarPath))

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
        require(myBuilder != null) { "The model builder is not available for building" }
        return myBuilder!!.build(modelConfiguration, experimentRunParameters, defaultKSLDatabaseObserverOption)
    }

    override fun close() {
        myBuilder = null
    }

}