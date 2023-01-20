package ksl.controls


import kotlin.reflect.KClass

/**
 * Defines the set of valid control types
 */
enum class ControlType(private val clazz: KClass<*>) {
    DOUBLE(Double::class),
    INTEGER(Int::class),
    LONG(Long::class),
    FLOAT(Float::class),
    SHORT(Short::class),
    BYTE(Byte::class),
    BOOLEAN(Boolean::class);

    fun asClass(): KClass<*> {
        return clazz
    }

    companion object {
        val classTypesToValidTypesMap = mapOf(
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