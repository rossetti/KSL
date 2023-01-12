/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.utilities

/** An interface for defining the name of an object
 */
interface NameIfc {
    /**
     *
     * @return a string representing the name of the object
     */
    val name: String
}

open class Name(override val name: String = "defaultName") : NameIfc

fun <T> makeNameFromClass(clazz: Class<T>, id: Int, str: String? = null): String {
    return if (str == null) {
        // no name is being passed, construct a default name
        var s = clazz.simpleName
        val k = s.lastIndexOf(".")
        if (k != -1) {
            s = s.substring(k + 1)
        }
        s + "_" + id
    } else {
        str
    }
}

/** An interface to defining the identity of an object in terms
 * of a name and a number
 */
interface IdentityIfc : NameIfc {
    /**
     *
     * @return an int representing the id of the object
     */
    val id: Int

    /**
     *  @return a changeable label associated with the object
     */
    var label: String?
}

open class Identity(aName: String? = null) : IdentityIfc, NameIfc {

    companion object {
        private var IDCounter: Int = 0
    }

    override val id: Int = ++IDCounter

    override val name: String = aName ?: ("ID_$id")

    override var label: String? = name

    override fun toString(): String {
        return "Identity(id=$id, name=$name, label=$label)"
    }

}

fun main() {
    val n1 = Identity("Manuel")
    val n2 = Identity("Joe")
    val n3 = Identity("Maria")
    val n4 = Identity()
    println(n1)
    println(n2)
    println(n3)
    println(n4)

}