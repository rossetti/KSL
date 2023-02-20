package ksl.utilities.io.dbutil

import ksl.controls.ControlType
import ksl.utilities.io.tabularfiles.TabularFile
import ksl.utilities.math.KSLMath
import kotlin.reflect.*
import kotlin.reflect.full.*

/** DbDataView represents a base class for constructing data classes
 * that work with instances of DatabaseIfc. Subclasses of this base class
 * represent tables that represent database views or results. There is no need
 * to push data from a DbDataView into a database. Views are for extracting data
 * from the database.
 *
 * Example usage:
 * ```
 * data class Person(var name:String, var age:Int): DbData("Persons")
 * db.selectDbDataInto(::Person)
 * ```
 * Assume that db hold an instance to a database that has a table called, Persons,
 * with the fields name and age as the sole columns, in that order. The data will be extracted
 * from the database table and instances of the data class created and filled
 * with the data from the table. As long as the data class properties match
 * in order and in with compatible types with the fields/columns of the database, then
 * the instances will be created and filled.
 *
 * The [tableName] should be a valid table name or view name within a database if
 * used with a database.
 */
abstract class DbData(val tableName: String) {

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
     *  and is the reflection property
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
     * class then nothing happens. The size of the supplied array must
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
        val map = extractAllPropertyValuesByName()
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

/** DbTableData represents a base class for constructing data classes
 * that work with instances of DatabaseIfc.  Specifically, DbTableData
 * provide the ability to push data into a table. Thus, subclasses of DbTableData
 * must provide information about the primary key of the table and whether
 * the key is an auto-increment type field.
 *
 * Example usage:
 * ```
 * data class Person(var id: Int, var name:String, var age:Int): DbTableData("Persons", listOf("id"), true)
 * db.selectDbDataInto(::Person)
 * ```
 * Assume that db hold an instance to a database that has a table called, Person,
 * with the fields (id, name and age) as the sole columns, in that order. The data will be extracted
 * from the database table and instances of the data class created and filled
 * with the data from the table. As long as the data class properties match
 * in order and with compatible types with the fields/columns of the database, then
 * the instances will be created and filled.
 *
 * The [tableName] should be a valid table name within a database if
 * used with a database.
 *
 * The property [keyFields] holds the names of the fields involved within the primary key.
 * There must be at least one field supplied. That is the list must not be empty. And, if
 * the auto-increment property is set to true, there must be one and only one element in the list.
 *
 * The property [autoIncField] indicates if the referenced table
 * has an auto-increment field as the primary key.  This information can be used
 * when pushing data from the data class into the database to ignore the
 * property listed in the constructor of the data class.
 */
abstract class DbTableData(
    tblName: String,
    val keyFields: List<String>,
    val autoIncField: Boolean = false
) : DbData(tblName) {
    init {
        require(keyFields.isNotEmpty()) { "The list of key fields must have at least 1 element" }
        if (autoIncField) {
            require(keyFields.size == 1) { "An auto-increment field was indicated but the number of key fields was not 1" }
        }
    }

    /**
     *  The number of fields to insert.  If there is an auto-increment field
     *  then it is not included.
     */
    val numInsertFields: Int
        get() = if (autoIncField) (numColumns - 1) else numColumns

    /**
     *  The number of fields to update. This does not include the fields within the
     *  primary key
     */
    val numUpdateFields: Int
        get() = numColumns - keyFields.size

    /**
     * Checks if an autoIncField exists
     */
    fun hasAutoIncrementField(): Boolean {
        return autoIncField
    }

    /**
     *  Extracts the names of the fields that can be updated or inserted by
     *  accounting for an auto-increment key field
     */
    fun extractUpdatableFieldNames(): List<String> {
        val names = extractPropertyNames().toMutableList()
        if (autoIncField) {
            // must have only 1 key field, remove it because it cannot be updated
            names.remove(keyFields.first())
        }
        return names
    }

    /**
     *  The map will contain the fields and their values that
     *  are designated as part of the primary key
     */
    fun extractKeyPropertyValuesByName(): Map<String, Any?> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyMap()
        }
        val map = extractAllPropertyValuesByName().toMutableMap()
        for (field in keyFields) {
            if (!map.containsKey(field)) {
                map.remove(field)
            }
        }
        return map
    }

    /**
     *  The map will contain the fields and their values that
     *  are not designated as part of the primary key
     */
    fun extractNonKeyPropertyValuesByName(): Map<String, Any?> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyMap()
        }
        val map = extractAllPropertyValuesByName().toMutableMap()
        for (field in keyFields) {
            if (map.containsKey(field)) {
                map.remove(field)
            }
        }
        return map
    }

    /**
     *  The map will contain all the fields by name with their
     *  values without an auto-increment field if it exists
     */
    fun extractNonAutoIncPropertyValuesByName(): Map<String, Any?> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyMap()
        }
        val map = extractAllPropertyValuesByName().toMutableMap()
        if (autoIncField) {
            map.remove(keyFields.first())
        }
        return map
    }

    /**
     *  Extracts the value of the public, mutable properties of a data class in the order
     *  in which they are declared in the primary constructor.
     *
     *  If the object is not an instance of a data class,
     *  then the returned list will be empty.
     *  @param autoInc if the data class as an auto increment field then
     *  the values are extracted without the fields value if this parameter is true.
     *  If false, all values are extracted regardless of auto increment field.
     *  The default is false.
     */
    fun extractPropertyValues(autoInc: Boolean = false): List<Any?> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyList()
        }
        val list = mutableListOf<Any?>()
        val names = extractPropertyNames().toMutableList()
        val pairs = extractAllPropertyValuesByName().toMutableMap()
        if (autoInc) {
            if (autoIncField) {
                names.remove(keyFields[0])
                pairs.remove(keyFields[0])
            }
        }
        for (name in names) {
            list.add(pairs[name])
        }
        return list
    }

    /**
     *  Extracts the value of the public, mutable properties of a data class in the order
     *  in which they are declared in the primary constructor not including the
     *  fields designated as being within the primary key. We assume that the values
     *  returned correspond to data that must be used to update a record within
     *  the database table.  Thus, we assume that the user will not update
     *  the values of the fields within the primary key. The returned
     *  values are in the order of the properties listed in the DbTableData
     *  class, not including the primary key fields.
     *
     *  If the object is not an instance of a data class,
     *  then the returned list will be empty.
     */
    fun extractUpdateValues(): List<Any?> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyList()
        }
        val list = mutableListOf<Any?>()
        val updateFields = extractPropertyNames().toMutableList()
        for (field in keyFields) {
            updateFields.remove(field)
        }
        val pairs = extractAllPropertyValuesByName().toMutableMap()
        for (field in updateFields) {
            list.add(pairs[field])
        }
        return list
    }

    /**
     *  Extracts the current value of the fields that are designated
     *  as part of the primary key in the order in which the properties
     *  are listed within the class definition
     */
    fun extractKeyValues(): List<Any?> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyList()
        }
        val list = mutableListOf<Any?>()
        val names = extractPropertyNames().toMutableList()
        val pairs = extractAllPropertyValuesByName()
        for (name in names) {
            if (keyFields.contains(name)) {
                list.add(pairs[name])
            }
        }
        return list
    }

    fun updateDataSQLStatement(): String {
        val updateFields = extractPropertyNames().toMutableList()
        for (field in keyFields) {
            updateFields.remove(field)
        }
        return DatabaseIfc.updateTableStatementSQL(tableName, updateFields, keyFields, schemaName)
    }

    fun insertDataSQLStatement(): String {
        val insertFields = extractUpdatableFieldNames()
        return DatabaseIfc.insertIntoTableStatementSQL(tableName, insertFields, schemaName)
    }

    fun setAutoIncField(value: Any?) {
        if (autoIncField) {
            val properties = extractMutableProperties()
            val property = properties[keyFields.first()]
            if (property is KMutableProperty<*>) {
                val p = property as KMutableProperty<*>
                p.setter.call(this, value)
            }
        }
    }
}

fun main() {
    val e = ExperimentTableData()
    val names = e.extractPropertyNames()
    println(names)
    val values = e.extractPropertyValues()
    println(values)
    val sList = listOf<Any?>(-1, "a", "b", "c", -1, false, null, null, null, true, false, false, true, 100, false)
    e.setPropertyValues(sList)
    println(e)

    val fields = listOf("A", "B", "C")
    val where = listOf("D", "E")
    val sql = DatabaseIfc.updateTableStatementSQL("baseball", fields, where, "league")
    println(sql)

    println("INSERT statement:")
    println(e.insertDataSQLStatement())
    println()
    println("UPDATE statement:")
    println(e.updateDataSQLStatement())
}
