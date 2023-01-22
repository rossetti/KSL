package ksl.controls

import ksl.utilities.NameIfc
import ksl.utilities.math.KSLMath
import mu.KLoggable
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.cast

/**
 * @param type    this is the type of T, e.g. if T is Double, then use Double::class. This allows
 *                the generic type to be easily determined at run-time, must not be null
 * @param element the model element that has the method to control, must not be null
 * @param setter  the property that will be used by the control, must not be null
 */
class Control<T : Any>(val type: KClass<T>, initialValue: T, val element: NameIfc, setter: KMutableProperty.Setter<*>) {

    init {
        require(hasControlAnnotation(setter)) { "The property ${mySetter.name} does not have a control annotation." }
        require(ControlType.classTypesToValidTypesMap.containsKey(type))
        { "The type ${type.simpleName} is not a valid control type." }
    }

    private val mySetter: KMutableProperty.Setter<*> = setter

    private val kslControl: KSLControl = controlAnnotation(setter)!!

    init {
        require(kslControl.controlType.asClass() == type)
        { "The annotation type ${kslControl.controlType.asClass().simpleName} is not compatible with control type ${type.simpleName} " }

//        logger.info(
//            "Constructed control : {} for property: {} on class {}",
//            this, mySetter.name, element.name
//        )
    }

    val setterName: String = if (annotationName == "") {
        mySetter.name
    } else {
        annotationName
    }

    var controlValue: T = initialValue
        private set

    val key: String
        get() {
            val en: String = element.name.replace(".", "\\.")
            val sn = setterName.replace(".", "\\.")
            return "$en.$sn"
        }

    val annotationType: ControlType
        get() = kslControl.controlType

    val annotationName: String
        get() = kslControl.name

    val lowerBound: Double
        get() = kslControl.controlType.coerceValue(kslControl.lowerBound)

    val upperBound: Double
        get() = kslControl.controlType.coerceValue(kslControl.upperBound)

    val annotationComment: String
        get() = kslControl.comment

    /**
     * Takes a double value and translates it to a valid value for the type of control.
     * This may involve a conversion to numeric types that have narrower numeric
     * representations as per the Java Language specification.
     * [...](https://docs.oracle.com/javase/specs/jls/se8/html/jls-5.html#jls-5.1.3)
     * The conversion varies from standard java by rounding up integral values. For example,
     * 4.99999 will be rounded to 5.0 rather than the standard truncation to 4.0.
     * If the double value is outside the range of the numeric type, then it is coerced to the
     * smallest or largest permissible value for the numeric type.  If the double value is
     * outside of upper and lower limits as specified by the control annotation, then the value
     * is coerced to the nearest limit. This method will call assignValue(T) with the appropriate
     * type for the control.  If the control type is Boolean, a 1.0 is coerced to true and any double not
     * equal to 1.0 is coerced to false.
     *
     * @param value the value to set
     */
    fun setValue(value: Double) {
        // the incoming value comes in as a double and must be converted to the range of the control type
        var x: Double = kslControl.controlType.coerceValue(value)
        // coerced value is within domain for the control type
        // now ensure value is within limits for control
        x = limitToRange(x)
        // x is now valid for type and limited to control range and can be converted to appropriate type
        // before assigning it
        val v = coerce(x)
        // finally make the assignment
        assignValue(v)
    }

    /**
     * Ensures that the supplied double is within the bounds
     * associated with the control annotation. This method does
     * not change the state of the control.
     *
     * @param value the value to limit
     * @return the limited value for future use
     */
    fun limitToRange(value: Double): Double {
        if (value <= lowerBound) {
            return lowerBound
        } else if (value >= upperBound) {
            return upperBound
        }
        return value
    }

    /**
     * Subclasses may need ot override this method if attempting to handle additional valid
     * control types.
     *
     * @param value the value to coerce to the type of the control
     * @return the coerced value
     */
    fun coerce(value: Double): T {
        // These should all be safe casts
        return when (annotationType) {
            ControlType.DOUBLE -> type.cast(value)
            ControlType.INTEGER -> type.cast(KSLMath.toIntValue(value))
            ControlType.LONG -> type.cast(KSLMath.toLongValue(value))
            ControlType.FLOAT -> type.cast(KSLMath.toFloatValue(value))
            ControlType.SHORT -> type.cast(KSLMath.toShortValue(value))
            ControlType.BYTE -> type.cast(KSLMath.toByteValue(value))
            ControlType.BOOLEAN -> type.cast(KSLMath.toBooleanValue(value))
        }
    }

    /**
     * Allows the direct assignment of a value of type T to the control, and thus
     * to the element that was annotated by the control. This allows for the possibility of
     * non-numeric types to be added in the future.  Use setValue(double) for any numeric types
     * as well as boolean values.
     *
     * @param value the value to assign
     */
    fun assignValue(value: T) {
        try {
            mySetter.call(element, value)
            // record the value last set
            // rather than try to read it from a getter on demand
            controlValue = value
            logger.info("Control {} was assigned value {}", key, controlValue)
        } catch (e: IllegalAccessException) {
            logger.error("Unsuccessful assign for Control {} with value {}", key, controlValue)
            throw RuntimeException(e)
        } catch (e: InvocationTargetException) {
            logger.error("Unsuccessful assign for Control {} with value {}", key, controlValue)
            throw RuntimeException(e)
        }
    }

    /**
     * Checks if the type of control can be converted to a double
     *
     * @return true if control can be converted to a double
     */
    fun isDoubleCompatible(): Boolean {
        return when (annotationType) {
            ControlType.DOUBLE,
            ControlType.INTEGER,
            ControlType.LONG,
            ControlType.FLOAT,
            ControlType.SHORT,
            ControlType.BYTE,
            ControlType.BOOLEAN -> true
        }
    }

    /**
     * Gets the last value of the control as a double. Use isDoubleCompatible() for
     * safe call
     *
     * @return the last value of the control converted to a double
     */
    fun lastValueAsDouble(): Double {
        return when (annotationType) {
            ControlType.DOUBLE,
            ControlType.INTEGER,
            ControlType.LONG,
            ControlType.FLOAT,
            ControlType.SHORT,
            ControlType.BYTE -> {
                controlValue as Double
            }
            ControlType.BOOLEAN -> {
                val b = controlValue as Boolean
                if (b) {
                    1.0
                } else 0.0
            }
        }
    }

    override fun toString(): String {
        val str = StringBuilder()
        str.append("[key = ").append(key)
        str.append(", control type = ").append(annotationType)
        str.append(", value = ").append(controlValue)
        str.append(", lower bound = ").append(lowerBound)
        str.append(", upper bound = ").append(upperBound)
        str.append(", comment = ").append(if (annotationComment == "") "\"\"" else annotationComment)
        str.append("]")
        return str.toString()
    }

    /**
     * class to define (and populate) a more detailed control record.
     *
     * @return the annotation control map
     */
    fun getControlRecord(): ControlRecord {
        return ControlRecord(this)
    }

    companion object : KLoggable {
        /**
         * A global logger for logging of model elements
         */
        override val logger = logger()

        fun <T> controlAnnotation(setter: KMutableProperty.Setter<T>): KSLControl? {
            return setter.annotations.filterIsInstance<KSLControl>().firstOrNull()
        }

        fun <T> hasControlAnnotation(setter: KMutableProperty.Setter<T>): Boolean {
            return setter.annotations.filterIsInstance<KSLControl>().isNotEmpty()
        }

    }
}