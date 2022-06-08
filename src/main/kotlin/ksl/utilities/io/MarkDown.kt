package ksl.utilities.io

import ksl.utilities.KSLArrays
import ksl.utilities.random.rvariable.NormalRV
import java.text.DecimalFormat
import kotlin.collections.ArrayList


/**
 * A class to facilitate construction of markdown elements
 */
object MarkDown {
    val D2FORMAT = DecimalFormat("0.##")
    val D3FORMAT = DecimalFormat("0.###")
    fun header(header: String?, level: Int): String {
        var level = level
        if (level <= 0) {
            level = 1
        }
        val sb = StringBuilder(header)
        sb.insert(0, " ")
        for (i in 1..level) {
            sb.insert(0, "#")
        }
        return sb.toString()
    }

    fun blockQuote(text: String?): String {
        val sb = StringBuilder(text)
        sb.insert(0, "> ")
        return sb.toString()
    }

    fun bold(text: String?): String {
        val sb = StringBuilder(text)
        sb.insert(0, "**")
        sb.append("**")
        return sb.toString()
    }

    fun italic(text: String?): String {
        val sb = StringBuilder(text)
        sb.insert(0, "*")
        sb.append("*")
        return sb.toString()
    }

    fun code(text: String?): String {
        val sb = StringBuilder(text)
        sb.insert(0, "`")
        sb.append("`")
        return sb.toString()
    }

    fun hRule(): String {
        val sb = StringBuilder()
        sb.append("___")
        sb.append(System.lineSeparator())
        return sb.toString()
    }

    fun boldAndItalic(text: String?): String {
        return italic(bold(text))
    }

    fun nList(list: List<String?>): String {
        val sb = StringBuilder()
        val i = 1
        for (s in list) {
            sb.append(i)
            sb.append(". ")
            sb.append(s)
            sb.append(System.lineSeparator())
        }
        return sb.toString()
    }

    fun unList(list: List<String?>): String {
        val sb = StringBuilder()
        for (s in list) {
            sb.append("- ")
            sb.append(s)
            sb.append(System.lineSeparator())
        }
        return sb.toString()
    }

    fun link(linkText: String?, linkURL: String?): String {
        val sb = StringBuilder(linkText)
        sb.insert(0, "[")
        sb.append("]")
        sb.append("(")
        sb.append(linkURL)
        sb.append(")")
        return sb.toString()
    }

    fun image(altText: String?, imageURL: String?): String {
        val sb = StringBuilder(link(altText, imageURL))
        sb.insert(0, "!")
        return sb.toString()
    }

    fun allLeft(nCols: Int): List<ColFmt> {
        return allSame(nCols, ColFmt.LEFT)
    }

    fun allRight(nCols: Int): List<ColFmt> {
        return allSame(nCols, ColFmt.RIGHT)
    }

    fun allCentered(nCols: Int): List<ColFmt> {
        return allSame(nCols, ColFmt.CENTER)
    }

    fun allSame(nCols: Int, format: ColFmt): List<ColFmt> {
        require(nCols > 0) { "The number of column must be >= 1" }
        val list: MutableList<ColFmt> = ArrayList()
        for (i in 1..nCols) {
            list.add(format)
        }
        return list
    }

    fun tableHeader(colHeaders: List<String>, format: ColFmt = ColFmt.LEFT): String {
        return tableHeader(colHeaders, allSame(colHeaders.size, format))
    }

    fun tableHeader(colHeaders: List<String>, formats: List<ColFmt>): String {
        require(!colHeaders.isEmpty()) { "The column headers list was empty" }
        require(!formats.isEmpty()) { "The column formats list was empty" }
        require(colHeaders.size == formats.size) { "The size of the header and format lists do not match" }
        val sb = StringBuilder()
        sb.append(System.lineSeparator())
        val h1 = colHeaders.joinToString("| ", "|", "|")
        sb.append(h1)
        sb.append(System.lineSeparator())
        val h2 = formats.joinToString("| ", "|", "|", transform = { it.fmt })
        sb.append(h2)
        return sb.toString()
    }

    fun tableRow(elements: List<String>): String {
        require(!elements.isEmpty()) { "The row elements list was empty" }
        val sb = StringBuilder()
        val h1 = elements.joinToString("| ", "|", "|")
        sb.append(h1)
        return sb.toString()
    }

    fun tableRow(rowLabel: String? = null, array: DoubleArray, df: DecimalFormat? = D3FORMAT): String {
        return if (rowLabel == null) {
            val data = KSLArrays.toStrings(array, df)
            tableRow(data.toList())
        } else {
            val data = KSLArrays.toStrings(array, df)
            val list: MutableList<String> = ArrayList()
            list.add(rowLabel)
            list.addAll(data.toList())
            tableRow(list)
        }
    }

    enum class ColFmt(val fmt: String) {
        LEFT(":---"), CENTER(":---:"), RIGHT("---:");
    }

    class Table(
        colHeaders: List<String>,
        formats: List<ColFmt> = allSame(colHeaders.size, ColFmt.LEFT)
    ) {
        private val sbTable = StringBuilder()
        private val numCols: Int

        constructor(colHeaders: List<String>, format: ColFmt) : this(colHeaders, allSame(colHeaders.size, format)) {}

        init {
            require(!colHeaders.isEmpty()) { "The column headers list was empty" }
            require(!formats.isEmpty()) { "The column formats list was empty" }
            require(colHeaders.size == formats.size) { "The size of the header and format lists do not match" }
            numCols = colHeaders.size
            sbTable.append(tableHeader(colHeaders, formats))
            sbTable.append(System.lineSeparator())
        }

        fun addRow(elements: List<String>): Table {
            require(elements.size == numCols) { "The size of the array does not match the number of columns" }
            sbTable.append(tableRow(elements))
            sbTable.append(System.lineSeparator())
            return this
        }

        fun addRow(data: DoubleArray): Table {
            return addRow(null, data!!, D3FORMAT)
        }

        fun addRow(rowLabel: String? = null, data: DoubleArray, df: DecimalFormat? = D3FORMAT): Table {
            if (rowLabel == null) {
                require(data.size == numCols) { "The size of the array does not match the number of columns" }
            } else {
                require(!(data.size != numCols - 1)) { "The size of the array does not match the number of columns" }
            }
            sbTable.append(tableRow(rowLabel, data, df))
            sbTable.append(System.lineSeparator())
            return this
        }

        fun addRows(data: Array<DoubleArray>, df: DecimalFormat? = D3FORMAT) {
            if (data.size == 0) {
                return
            }
            for (array in data) {
                addRow(null, array, df)
            }
        }

        fun addRows(rows: List<DoubleArray>, df: DecimalFormat? = D3FORMAT) {
            if (rows.isEmpty()) {
                return
            }
            for (array in rows) {
                addRow(null, array, df)
            }
        }

        override fun toString(): String {
            return sbTable.toString()
        }
    }
}

fun main() {
    val s = MarkDown.header("manuel", 2)
    println(s)
    val b = MarkDown.bold("manuel")
    println(b)
    val header = listOf("x", "y", "z")
    val h = MarkDown.tableHeader(header)
    println(h)
    val n = NormalRV()
    val t = MarkDown.Table(header)
    for (i in 1..10) {
        t.addRow(n.sample(3))
    }
    println(t)
}