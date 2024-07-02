package ksl.utilities.io.dbutil

import ksl.utilities.io.tabularfiles.DataType
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KType

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
 *
 * Ideally, the data class should be defined with 'var' properties that have default
 * values. This facilitates the creation of instances.  Any 'val' properties are
 * not recognized as table fields because they are not mutable.  You can have
 * 'val' properties in the primary constructor or in the class body; however, they
 * will not be processed as table fields.
 * 
 * Assume that db holds an instance to a database that has a table called, Persons,
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
) : TabularData(tblName) {
    init {
        require(keyFields.isNotEmpty()) { "The list of key fields must have at least 1 element" }
        if (autoIncField) {
            require(keyFields.size == 1) { "An auto-increment field was indicated but the number of key fields was not 1" }
        }
        val cls: KClass<out Any> = this::class
        require(cls.isData) {"The class ${cls.qualifiedName} must be a data class." }
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
     *  @param autoInc if the data class has an auto increment field then
     *  the values are extracted without the auto-increment field's value if this parameter is true.
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

    /**
     *  Returns a string representation of a table update statement.
     *  See [DatabaseIfc.updateTableStatementSQL]
     */
    fun updateDataSQLStatement(): String {
        val updateFields = extractPropertyNames().toMutableList()
        for (field in keyFields) {
            updateFields.remove(field)
        }
        return DatabaseIfc.updateTableStatementSQL(tableName, updateFields, keyFields, schemaName)
    }

    /**
     *  Returns a string representation of a table insert statement.
     *  See [DatabaseIfc.insertIntoTableStatementSQL]
     */
    fun insertDataSQLStatement(): String {
        val insertFields = extractUpdatableFieldNames()
        return DatabaseIfc.insertIntoTableStatementSQL(tableName, insertFields, schemaName)
    }

    /**
     *  Sets the value of the auto-increment field based on the supplied [value]
     */
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

    /**
     *   Creates a string representation of a CREATE TABLE SQL statement
     *   that could create a table that would hold the data associated with
     *   the DbTableData. The resulting string maps the
     *   property KTypes to suitable SQL types via [DbTableData.toSQLTypeString].
     *   If there is a primary key specification, it is captured in the
     *   CREATE TABLE statement.  Any NOT NULL specifications are also captured.
     *   There will not be any foreign key specifications because DbTableData
     *   does not specify them.  Also, since there is no SQL standard for
     *   an auto-increment primary key, the [autoIncField] specification is ignored.
     *   The purpose here is to get a quick and dirty table representation.
     *   If additional specifications are required the user could formulate ALTER TABLE
     *   statements or better yet use one of the many libraries available for
     *   more advanced SQL work. If [schemaName] is supplied, then the CREATE TABLE
     *   statement begins with "CREATE TABLE schemaName.tableName ". If the schema name
     *   is not specified, the statement begins with "CREATE TABLE tableName "
     */
    fun createTableSQLStatement(): String {
        if (autoIncField){
            DatabaseIfc.logger.warn { "Created a CREATE SQL statement for $tableName but auto-increment field was true." }
        }
        val start = if (!schemaName.isNullOrEmpty()) {
            "CREATE TABLE ${schemaName}.$tableName ("
        } else {
            "CREATE TABLE $tableName ( "
        }
        val sql = StringBuilder(start)
        sql.appendLine()
        val pm = extractMutableProperties()
        val pNames = extractPropertyNames()
        for(name in pNames) {
            val property = pm[name]!!
            val kType = property.returnType
            sql.append(name).append(" ")
            sql.append(toSQLTypeString(kType))
            if (!kType.isMarkedNullable){
                sql.append(" NOT NULL")
            }
            sql.appendLine(",")
        }
        // process primary key fields
        if (keyFields.isNotEmpty()) {
            sql.append("PRIMARY KEY")
            sql.appendLine(keyFields.joinToString(prefix = "(", separator = ",", postfix = ")"))
        }
        sql.appendLine(")")
        return sql.toString()
    }

    companion object {

        /**
         *  If the [kType] is {Double, Long, Integer, Boolean, Float, Short, Byte}
         *  then a string for the appropriate SQL type is returned
         *  ("DOUBLE", "BIGINT", "INTEGER", "BOOLEAN", "REAL", "SMALLINT", "SMALLINT").
         *  Any other KType is returned as "VARCHAR($defaultVarCharLength)"
         *  The default VARCHAR size is 512.
         */
        fun toSQLTypeString(kType: KType, defaultVarCharLength: Int = 512): String {
            if (kType.classifier == Double::class) {
                return "DOUBLE"
            } else if (kType.classifier == Int::class) {
                return "INTEGER"
            } else if (kType.classifier == Long::class) {
                return "BIGINT"
            } else if (kType.classifier == Boolean::class) {
                return "BOOLEAN"
            } else if (kType.classifier == Float::class) {
                return "REAL"
            } else if (kType.classifier == Short::class) {
                return "SMALLINT"
            } else if (kType.classifier == Byte::class) {
                return "SMALLINT"
            } else {
                return "VARCHAR($defaultVarCharLength)"
            }
        }
    }
}