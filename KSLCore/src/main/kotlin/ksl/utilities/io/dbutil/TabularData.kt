package ksl.utilities.io.dbutil

import ksl.utilities.io.tabularfiles.DataType
import ksl.utilities.io.tabularfiles.RowGetterIfc
import ksl.utilities.io.tabularfiles.TabularFile
import ksl.utilities.math.KSLMath
import kotlin.reflect.*
import kotlin.reflect.full.*

/** TabularData represents a base class for constructing data classes
 * that hold tabular data. Only base types can be represented.
 * Numeric columns can be represented by (Double, Int, Long, Short,
 * Byte, Float, and Boolean). Boolean is considered numeric via conversion
 * with 1 true and 0 false.  Non-numeric fields are represented by String. Complex
 * data types are not represented.
 *
 * Subclasses of this base class can be used
 * represent database tables that represent data views or results.
 *
 * A usage of TabularData is to hold data extracted from a database table,
 * view, or result set.
 *
 * Example usage:
 * ```
 * data class Person(var name:String, var age:Int): TabularData("Persons")
 * db.selectDbDataInto(::Person)
 * ```
 * Assume that db holds an instance to a database that has a table called, Persons,
 * with the fields name and age as the sole columns, in that order. The data will be extracted
 * from the database table and instances of the data class created and filled
 * with the data from the table. As long as the data class properties match
 * in order and with compatible types with the fields/columns of the database, then
 * the instances will be created and filled.
 *
 * If used within the context of a database, the [tableName] should be a valid table name or view name
 * within the database.
 */
abstract class TabularData(val tableName: String) {

    /**
     *  The optional name of the schema holding the table for the related data
     */
    var schemaName: String? = null

    /**
     *  The number of columns of data. The number of public mutable properties
     *  including any auto-increment field
     */
    val numColumns: Int
        get() = extractPropertyNames().size

    /**
     *  Extracts the names of the public, mutable properties of a data class
     *  in the order in which they are declared in the primary constructor.
     *
     *  If the object is not an instance of a data class,
     *  then the returned list will be empty.
     */
    fun extractPropertyNames(): List<String> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyList()
        }
        val list = mutableListOf<String>()
        val parameters: List<KParameter>? = cls.primaryConstructor?.parameters
        val pairs = extractMutableProperties()
        if (parameters != null) {
            for (param in parameters) {
                if (pairs.containsKey(param.name!!)) {
                    if (pairs[param.name!!] is KMutableProperty<*>) {
                        list.add(param.name!!)
                    }
                }
            }
        }
        return list
    }

    /**
     *  Extracts the property of the public mutable properties of a data class
     *  If the object is not an instance of a data class,
     *  then the returned map will be empty. The map contains the pairs
     *  of (name, property) where name is the name of the public property
     *  and property is the reflection property
     */
    fun extractMutableProperties(): Map<String, KProperty1<out Any, *>> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyMap()
        }
        val map = mutableMapOf<String, KProperty1<out Any, *>>()
        val properties: Collection<KProperty1<out Any, *>> = cls.memberProperties
        for (property in properties) {
            if (property.visibility == KVisibility.PUBLIC) {
                if (property is KMutableProperty<*>) {
                    map[property.name] = property
                }
            }
        }
        return map
    }

    /**
     *  Extracts the property of the public mutable properties of a data class
     *  Classifies each property whether it can be converted to a numeric value
     *  via isNumericConvertable(). All non-numeric mutable properties are
     *  considered TEXT; otherwise, they are considered NUMERIC.
     *  @return the map of types with the key being the name of the property
     *  and the value being its DataType. For use with TabularFile.
     */
    fun extractColumnDataTypes() : Map<String, DataType>{
        val map = mutableMapOf<String, DataType>()
        val mp = extractMutableProperties()
        val names = extractPropertyNames()
        for (name in names){
            val p  = mp[name]!!
            if (isNumericConvertable(p.returnType)){
                map[name] = DataType.NUMERIC
            } else {
                map[name] = DataType.TEXT
            }
        }
        return map
    }

    /**
     *  Extracts the values of the public, mutable properties of a data class in the order
     *  in which they are declared in the primary constructor.
     *
     *  If the object is not an instance of a data class,
     *  then the returned list will be empty.
     */
    fun extractPropertyValues(): List<Any?> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyList()
        }
        val list = mutableListOf<Any?>()
        val names = extractPropertyNames()
        val pairs = extractAllPropertyValuesByName()
        for (name in names) {
            list.add(pairs[name])
        }
        return list
    }

    /**
     *  Extracts the value of the public mutable properties of a data class
     *  If the object is not an instance of a data class,
     *  then the returned map will be empty. The map contains the pairs
     *  of (name, value) where name is the name of the public, mutable property
     *  and value is the current value of the property
     */
    fun extractAllPropertyValuesByName(): Map<String, Any?> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyMap()
        }
        val map = mutableMapOf<String, Any?>()
        val properties: Collection<KProperty1<out Any, *>> = cls.memberProperties
        for (property in properties) {
            if (property.visibility == KVisibility.PUBLIC) {
                if (property is KMutableProperty<*>) {
                    val v: Any? = property.getter.call(this)
                    map[property.name] = v
                }
            }
        }
        return map
    }

    /**
     * Sets the values of the public mutable properties of a data class to
     * the values supplied. If the object is not an instance of a data
     * class then nothing happens. The row from a TabularFile must map
     * to the property values
     */
    fun setPropertyValues(row: RowGetterIfc){
        setPropertyValues(row.elements)
    }

    /**
     * Sets the values of the public mutable properties of a data class to
     * the values supplied. If the object is not an instance of a data
     * class then nothing happens. The size of the supplied list must
     * be the same as the number of the mutable properties and
     * the type of each element in the supplied list must match the type
     * of the mutable property
     */
    fun setPropertyValues(values: List<Any?>) {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return
        }
        val names = extractPropertyNames()
        // check the number of properties
        require(names.size == values.size) { "The data class has ${names.size} properties, but ${values.size} values were supplied" }
        //val map = extractAllPropertyValuesByName()
        // check the type of the properties
        val properties = extractMutableProperties()
        for ((index, name) in names.withIndex()) {
            val property = properties[name]
            if (property is KMutableProperty<*>) {
                val p = property as KMutableProperty<*>
                val value: Any? = values[index]
                val rt = p.returnType
                if (rt.isMarkedNullable && (value == null)) {
                    // property can be null and the value was null, can just set it
                    p.setter.call(this, null)
                } else {
                    // property should not be null, value cannot be null
                    require(value != null) { "$tableName : The value at index $index was null and the property $name was not nullable" }
                    // value is not null, determine if types are the same
                    if (rt.classifier == value::class) {
                        p.setter.call(this, value)
                    } else {
                        // not of the same type, can it be converted for the assignment of value to property?
                        if (isNumericConvertable(rt, value)) {
                            p.setter.call(this, convertToNonNullableType(rt, value))
                        } else {
                            val msg =
                                "$tableName : the property ${p.name} of type ${rt.classifier} could not be converted to type ${value::class}"
                            DatabaseIfc.logger.error { msg }
                            throw IllegalStateException(msg)
                        }
                    }
                }
            }
        }
    }

    companion object {

        /**
         *  Checks if the KType can be converted to a numeric value.  For the purposes
         *  of this method, Boolean can be converted to number 1 = true and 0 = false
         */
        fun isNumericConvertable(kType: KType): Boolean {
            when (kType.classifier) {
                Double::class -> {
                    return true
                }
                Int::class -> {
                    return true
                }
                Long::class -> {
                    return true
                }
                Boolean::class -> {
                    return true
                }
                Short::class -> {
                    return true
                }
                Byte::class -> {
                    return true
                }
                Float::class -> {
                    return true
                }
                else -> {
                    return false
                }
            }
        }

        /**
         *  For the purposes of this method, Double, Int, Float, Long, Short, Byte,
         *  and Boolean are all numerically convertable
         */
        fun isNumericConvertable(value: Any): Boolean {
            return TabularFile.isNumeric(value)
        }

        /**
         *  Checks if the value can be converted to the KType
         *  If the types are not the same then we check if they
         *  are numbers. We assume, perhaps with some coercion that
         *  numbers can be converted to each other. There may be loss of precision during
         *  the conversion process. Also, we assume that Boolean can be
         *  converted to 1 or 0 and numbers can be converted to Boolean where
         *  value >=1 --> true, value < 1 --> false.  If the underlying types are the
         *  same then this method will always return true.
         */
        fun isNumericConvertable(kType: KType, value: Any): Boolean {
            return isNumericConvertable(kType) && isNumericConvertable(value)
        }

        fun convertToNonNullableType(kType: KType, value: Any): Any {
            if (kType.classifier == value::class) {
                // same type, no conversion needed
                return value
            }
            require(
                isNumericConvertable(
                    kType,
                    value
                )
            ) { "The KType (${kType.classifier}) and the value type ${value::class} cannot be converted" }
            // convert the value to Double
            val x: Double = TabularFile.asDouble(value)
            // now need to convert the double value based on the type of KType
            when (kType.classifier) {
                Double::class -> {
                    return x
                }
                Int::class -> {
                    return KSLMath.toIntValue(x)
                }
                Long::class -> {
                    return KSLMath.toLongValue(x)
                }
                Boolean::class -> {
                    return KSLMath.toBooleanValue(x)
                }
                Short::class -> {
                    return KSLMath.toShortValue(x)
                }
                Byte::class -> {
                    return KSLMath.toByteValue(x)
                }
                Float::class -> {
                    return KSLMath.toFloatValue(x)
                }
                else -> {
                    throw IllegalStateException("The value of type (${value::class}) cannot be converted to type ($kType) ")
                }
            }
        }
    }

}

