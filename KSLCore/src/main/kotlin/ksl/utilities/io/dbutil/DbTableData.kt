package ksl.utilities.io.dbutil

import kotlin.reflect.*
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability

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
        val names = extractPropertyNames().toMutableList()
        val pairs = extractPropertyValuesByName().toMutableMap()
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
    fun extractPropertyValuesByName(): Map<String, Any?> {
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
        val map = extractPropertyValuesByName()
        // check the type of the properties
        val properties = extractMutableProperties()
        for ((index, name) in names.withIndex()) {
            val value = values[index]
            if (value != null) {
                // get the property's return type
                val rt: KType? = properties[name]?.returnType
                if (rt != null) {
                    val vc: KClass<out Any> = value::class
                    val vcKType = if (rt.isMarkedNullable) {
                        vc.starProjectedType.withNullability(true)
                    } else {
                        vc.starProjectedType
                    }
                    require(rt == vcKType) { "The type ($rt) of property $name was not compatible with the corresponding type ($vcKType) of value $value at index $index" }
                }
            } else {
                // value was null, check if property was marked nullable
                // get the property's return type
                val rt: KType? = properties[name]?.returnType
                if (rt != null) {
                    require(rt.isMarkedNullable) { "The value at index $index was null and the property $name was not nullable" }
                }
            }
        }

        for ((index, name) in names.withIndex()) {
            val property = properties[name]
            if (property is KMutableProperty<*>) {
                val p = property as KMutableProperty<*>
                p.setter.call(this, values[index])
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
        val pairs = extractPropertyValuesByName().toMutableMap()
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
        val pairs = extractPropertyValuesByName().toMutableMap()
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
        val pairs = extractPropertyValuesByName()
        for(name in names){
            if (keyFields.contains(name)){
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
        val insertFields = extractPropertyNames().toMutableList()
        var nc = numColumns
        if (autoIncField) {
            nc = nc - 1
        }
        return DatabaseIfc.insertIntoTableStatementSQL(tableName, nc, schemaName)
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
