package ksl.utilities.io.dbutil

import kotlin.reflect.*
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability

/** DbData represents a base class for constructing data classes
 * that work with instances of DatabaseIfc.
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
 * used with a database. The property [autoIncField] indicates if the referenced table
 * has an auto-increment field as the primary key.  This information can be used
 * when pushing data from the data class into the database to ignore the
 * property listed in the constructor of the data class.
 */
abstract class DbData(val tableName: String, val autoIncField: String? = null) {

    /**
     * Checks if an autoIncField exists
     */
    fun hasAutoIncrementField() : Boolean {
        return autoIncField != null
    }

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
        if (autoInc){
            if (autoIncField != null){
                names.remove(autoIncField)
                pairs.remove(autoIncField)
            }
        }
        for (name in names) {
            list.add(pairs[name])
        }
        return list
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
            if (value != null){
                // get the property's return type
                val rt: KType? = properties[name]?.returnType
                if (rt != null){
                    val vc: KClass<out Any> = value::class
                    val vcKType = if (rt.isMarkedNullable){
                        vc.starProjectedType.withNullability(true)
                    } else {
                        vc.starProjectedType
                    }
                    require (rt == vcKType){ "The type ($rt) of property $name was not compatible with the corresponding type ($vcKType) of value $value at index $index" }
                }
            } else {
                // value was null, check if property was marked nullable
                // get the property's return type
                val rt: KType? = properties[name]?.returnType
                if (rt != null){
                    require(rt.isMarkedNullable){"The value at index $index was null and the property $name was not nullable"}
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
}

fun main(){
    val e = ExperimentData()
    val names = e.extractPropertyNames()
    println(names)
    val values = e.extractPropertyValues()
    println(values)
    val sList = listOf<Any?>(-1, "a","b" , "c", -1, false, null, null, null, true, false, false, true, 100, false)
    e.setPropertyValues(sList)
    println(e)
}
