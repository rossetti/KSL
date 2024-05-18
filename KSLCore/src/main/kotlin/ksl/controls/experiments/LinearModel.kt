package ksl.controls.experiments

import com.google.common.collect.Sets
import ksl.controls.experiments.LinearModel.Companion.allTerms

/**
 *  This class provides the ability to specify a linear model (for use in regression and
 *  design of experiments). This is only a string specification of the model. The terms
 *  are specified by the names of the factors.
 *
 *  Example: A model with 3 main effects, "A", "B", "C", with full model = "A B C A*B A*C B*C A*B*C".
 *     // y = b0 + b1*A + b2*B + b3*C + b12*AB + b13*AC + b23*BC + b123*ABC
 *     val m3 = LinearModel(setOf("A", "B", "C"))
 *     m3.specifyAllTerms()
 *
 *   The properties termsAsMap and termsAsList provide access to the specification.
 *
 *   @param mainEffects the names of the factors (regressors) in a set
 *   @param includeMainEffects starts the model with only the main effects. The default is true.
 */
class LinearModel(val mainEffects: Set<String>, includeMainEffects: Boolean = true) {

    private val myTerms: MutableMap<String, List<String>> = mutableMapOf()

    /**
     *  By default, we assume an intercept term
     */
    var intercept: Boolean = true

    /**
     * Returns the terms of the model as a map. The key to the
     * map is the string join of the elements in the associated list
     * with the elements separated by '*'.
     */
    val termsAsMap: Map<String, List<String>>
        get() = myTerms

    /**
     *  Returns the terms of the model as a list of lists
     */
    val termsAsList: List<List<String>>
        get() {
            val list = mutableListOf<List<String>>()
            for((key, term) in myTerms){
                list.add(term)
            }
            return list
        }

    init {
        if (includeMainEffects) {
            for (f in mainEffects) {
                myTerms[f] = listOf(f)
            }
        }
    }

    /**
     *  Every string in the list must be within the main effects set
     *  to be valid.
     */
    fun isValidTerm(list: List<String>): Boolean {
        if (list.isEmpty()) {
            return false
        }
        for (term in list) {
            if (!mainEffects.contains(term)) {
                return false
            }
        }
        return true
    }

    /**
     *  Repeatedly calls term() with the elements in the list
     *  to specify an entire model.
     */
    fun specify(list: List<List<String>>) {
        for (e in list) {
            term(e)
        }
    }

    /**
     *  Specifies a model with all terms (main effects, first order interactions,
     *  2nd order interactions, etc.
     */
    fun specifyAllTerms() {
        specify(allTerms(mainEffects))
    }

    /**
     *  Assumes a parsable string and converts it to a list of terms
     *  for specifying the model from the string.
     */
    fun parseFromString(parseString: String) {
        specify(parse(parseString))
    }

    /**
     *  Add a term to the model. It is assumed that the list of strings
     *  represents a product term. Thus, listOf("A", "B") is the interaction
     *  term "A*B". The elements of the list must be valid single (main) effect
     *  term names.
     */
    fun term(list: List<String>) {
        require(isValidTerm(list)) { "The list had invalid elements" }
        if (list.size == 1) {
            // adding a main effect
            myTerms[list[0]] = list
            return
        }
        // two or more elements in the list, form the key
        val key = list.joinToString("*")
        myTerms[key] = list
    }

    /**
     *  Shorthand for adding a two-way interaction term. The
     *  names must be different.
     */
    fun twoWay(name1: String, name2: String) {
        require(name1 != name2) { "The two way interaction must have different names" }
        term(listOf(name1, name2))
    }

    /**
     *  Shorthand for adding a three-way interaction term. The
     *  names must be different.
     */
    fun threeWay(name1: String, name2: String, name3: String) {
        require((name1 != name2) && (name1 != name3) && (name2 != name3)) { "The three way interaction must have different names" }
        term(listOf(name1, name2, name3))
    }

    /**
     *  Shorthand for adding an n-way interaction term.
     */
    fun nWay(set: Set<String>) {
        term(set.toList())
    }

    /**
     *  Shorthand for adding a quadratic term.
     */
    fun quadratic(name: String) {
        term(listOf(name, name))
    }

    /**
     *  Shorthand for adding a cubic term.
     */
    fun cubic(name: String) {
        term(listOf(name, name, name))
    }

    fun asString(): String {
        return myTerms.keys.sortedBy { it.length }.joinToString(separator = " ")
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Linear Model:")
        val list = myTerms.keys.sortedBy { it.length }
        for (s in list) {
            sb.appendLine("\t$s")
        }
        return sb.toString()
    }

    companion object {
        /**
         *  Parses the string into a list of lists that represents the model.
         *  Assumes that terms are specified with a space between them
         *  and '*' representing multiplication:
         *
         *  "A B C A*B A*C B*C A*B*C" represents a full order linear model
         */
        fun parse(model: String): List<List<String>> {
            val list = model.split(" ")
            val m = mutableListOf<List<String>>()
            for(s in list){
                if (s.length == 1){
                    m.add(listOf(s))
                } else {
                    // split the string by '*'
                    val tmp = s.split("*")
                    m.add(tmp)
                }
            }
            return m
        }

        /**
         *  Forms a model specification with all first order,
         *  2nd order, 3rd order, etc. interactions
         */
        fun allTerms(set: Set<String>): List<List<String>> {
            val m = mutableListOf<List<String>>()
            val ps: MutableSet<MutableSet<String>> = Sets.powerSet(set)
            for(s in ps){
                if(s.isNotEmpty()){
                    m.add(s.toList())
                }
            }
            m.sortBy { it.size }
            return  m
        }
    }

}

fun main() {
    val factors = setOf("A", "B", "C")
    val model = LinearModel(factors, true)
    model.term(listOf("A", "B"))
    model.quadratic("A")
    model.cubic("C")
    println(model.asString())

    val list = LinearModel.parse("A B C A*B A*C B*C A*B*C")
    val m2 = LinearModel(factors, true)
    m2.specify(list)
    println(m2.toString())
    println()
    val ps: MutableSet<MutableSet<String>> = Sets.powerSet(factors)
    for(s in ps){
        println(s)
    }
    println()
    val ts = allTerms(setOf("A", "B", "C", "D"))
    for(s in ts){
        println(s)
    }
    println()
    val m3 = LinearModel(setOf("A", "B", "C", "D"))
    m3.specifyAllTerms()
    println(m3.toString())
    println()
    println(m3.asString())

}