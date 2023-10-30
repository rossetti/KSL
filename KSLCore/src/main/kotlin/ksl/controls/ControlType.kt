package ksl.controls


import ksl.utilities.math.KSLMath
import ksl.utilities.random.rvariable.toDouble
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Defines the set of valid control types
 */
enum class ControlType(private val clazz: KClass<*>) {
    DOUBLE(Double::class) {
        override fun coerceValue(value: Double): Double {
            return value
        }
    },
    INTEGER(Int::class) {
        override fun coerceValue(value: Double): Double {
            return KSLMath.toIntValue(value).toDouble()
        }
    },
    LONG(Long::class) {
        override fun coerceValue(value: Double): Double {
            return KSLMath.toLongValue(value).toDouble()
        }
    },
    FLOAT(Float::class) {
        override fun coerceValue(value: Double): Double {
            return KSLMath.toFloatValue(value).toDouble()
        }
    },
    SHORT(Short::class) {
        override fun coerceValue(value: Double): Double {
            return KSLMath.toShortValue(value).toDouble()
        }
    },
    BYTE(Byte::class) {
        override fun coerceValue(value: Double): Double {
            return KSLMath.toByteValue(value).toDouble()
        }
    },
    BOOLEAN(Boolean::class) {
        override fun coerceValue(value: Double): Double {
            return KSLMath.toBooleanValue(value).toDouble()
        }
    };

    fun asClass(): KClass<*> {
        return clazz
    }

    abstract fun coerceValue(value: Double): Double

    companion object {

        fun coerceToDouble(call: Any?): Double {
            // call must be some valid "numeric type"
            if (call == null){
                return 0.0
            }
            if (call is Double){
                return call
            } else if (call is Int){
                return call.toDouble()
            } else if (call is Long){
                return call.toDouble()
            } else if (call is Float){
                return call.toDouble()
            } else if (call is Short){
                return call.toDouble()
            } else if (call is Byte){
                return call.toDouble()
            } else if (call is Boolean){
                return call.toDouble()
            } else {
                throw IllegalStateException("The property type cannot be converted to Double ")
            }
        }
        fun validType(type: KType) : Boolean {
            return classTypesToValidTypesMap.contains(type.classifier)
        }

        val classTypesToValidTypesMap: Map<KClass<out Any>, ControlType> = mapOf(
            Double::class to DOUBLE,
            Int::class to INTEGER,
            Long::class to LONG,
            Float::class to FLOAT,
            Short::class to SHORT,
            Byte::class to BYTE,
            Boolean::class to BOOLEAN
        )
    }
}