package ksl.examples.general.utilities

import ksl.utilities.statistic.WelchANOVA

fun main() {
    val dataMap = mapOf(
        "Group1" to doubleArrayOf(
            0.49959,
            0.23457,
            0.26505,
            0.27910,
            0.00000,
            0.00000,
            0.00000,
            0.14109,
            0.00000,
            1.34099
        ),
        "Group2" to doubleArrayOf(
            0.24792,
            0.00000,
            0.00000,
            0.39062,
            0.34841,
            0.00000,
            0.20690,
            0.44428,
            0.00000,
            0.31802
        ),
        "Group3" to doubleArrayOf(
            0.25089,
            0.00000,
            0.00000,
            0.00000,
            0.11459,
            0.79480,
            0.17655,
            0.00000,
            0.15860,
            0.00000
        ),
        "Group4" to doubleArrayOf(
            0.37667,
            0.43561,
            0.72968,
            0.26285,
            0.22526,
            0.34903,
            0.24482,
            0.41096,
            0.08679,
            0.87532
        )
    )
    val anova = WelchANOVA(dataMap)
    anova.print()
}