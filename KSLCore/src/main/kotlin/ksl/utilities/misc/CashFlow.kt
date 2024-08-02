package ksl.utilities.misc

import kotlin.math.pow

class CashFlow(rate: Double = 0.1, cashFlows: DoubleArray) {
    init {
        require(rate > -1.0) { "invalid interest rate" }
    }

    var interestRate = rate
        set(value) {
            require(value > -1.0) { "invalid interest rate" }
            field = value
        }

    private val myFlows: DoubleArray = cashFlows.copyOf()

    val presentWorth: Double
        get() = presentWorth(interestRate, myFlows)

    val futureWorth: Double
        get() = futureWorthGivenP(interestRate, myFlows.size - 1, presentWorth)

    val annualWorth: Double
        get() = annualizedWorthGivenP(interestRate, myFlows.size - 1, presentWorth)

    val size: Int
        get() = myFlows.size

    operator fun CashFlow.set(index: Int, value: Double) {
        myFlows[index] = value
    }

    operator fun CashFlow.get(index: Int): Double {
        return myFlows[index]
    }

    fun setFlows(cashFlows: DoubleArray) {
        require(myFlows.size == cashFlows.size) { "The size of the cash flows does not match" }
        for ((i, f) in cashFlows.withIndex()) {
            myFlows[i] = f
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("CashFlow ")
        sb.appendLine("interest rate = $interestRate")
        for (i in myFlows.indices) {
            sb.appendLine("flow[$i] = ${myFlows[i]}")
        }
        sb.appendLine("Present Worth = $presentWorth")
        sb.appendLine("Future Worth = $futureWorth")
        sb.appendLine("Annual Worth = $annualWorth")
        return sb.toString()
    }

    companion object {

        fun presentWorth(i: Double, cashFlow: DoubleArray): Double {
            require(i > -1.0) { "interest rate was <= -1" }
            require(cashFlow.isNotEmpty()) { "The cash flow was empty." }
            var pw = cashFlow[0]
            for (t in 1 until cashFlow.size) {
                pw = pw + presentWorthGivenF(i, t, cashFlow[t])
            }
            return pw
        }

        fun presentWorthGivenF(i: Double, n: Int, f: Double): Double {
            require(i > -1.0) { "interest rate was <= -1" }
            require(n >= 0) { "number of periods was < 0" }
            val d = (1.0 + i).pow(n.toDouble())
            return f / d
        }

        fun presentWorthGivenA(i: Double, n: Int, a: Double): Double {
            require(i > -1.0) { "interest rate was <= -1" }
            require(n >= 0) { "number of periods was < 0" }
            val d = (1.0 + i).pow(n.toDouble())
            return a * ((d - 1.0) / (i * d))
        }

        fun futureWorthGivenP(i: Double, n: Int, p: Double): Double {
            require(i > -1.0) { "interest rate was <= -1" }
            require(n >= 0) { "number of periods was < 0" }
            val d = (1.0 + i).pow(n.toDouble())
            return p * d
        }

        fun annualizedWorthGivenP(i: Double, n: Int, p: Double): Double {
            require(i > -1.0) { "interest rate was <= -1" }
            require(n >= 0) { "number of periods was < 0" }
            val d = (1.0 + i).pow(n.toDouble())
            return p * (i * d / (d - 1.0))
        }

        fun futureWorthGivenA(i: Double, n: Int, a: Double): Double {
            require(i > -1.0) { "interest rate was <= -1" }
            require(n >= 0) { "number of periods was < 0" }
            val d = (1.0 + i).pow(n.toDouble())
            return a * ((d - 1.0) / i)
        }

        fun annualizedWorthGivenF(i: Double, n: Int, f: Double): Double {
            require(i > -1.0) { "interest rate was <= -1" }
            require(n >= 0) { "number of periods was < 0" }
            val d = (1.0 + i).pow(n.toDouble())
            return f * (i / (d - 1.0))
        }
    }
}