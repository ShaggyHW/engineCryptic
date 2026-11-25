package com.projectx.puzzle.combolock

private val NUMERAL_BY_VALUE = mapOf(1 to 'I', 5 to 'V', 10 to 'X', 50 to 'L', 100 to 'C', 500 to 'D', 1000 to 'M')

internal fun romanNumeral(value: Int): Char? = NUMERAL_BY_VALUE[value]
