package com.projectx.script.api

import kotlin.test.Test
import kotlin.test.assertEquals

class BankQuantityOpTest {

    @Test
    fun `fixed amounts use their own ops`() {
        assertEquals(2, bankQuantityOp(1))
        assertEquals(3, bankQuantityOp(5))
        assertEquals(4, bankQuantityOp(10))
        assertEquals(7, bankQuantityOp(0))
    }

    @Test
    fun `fifty and other amounts go through X, never the last-X op`() {
        for (amount in listOf(2, 14, 28, 50, 100, 1000)) assertEquals(-1, bankQuantityOp(amount), "amount $amount")
    }
}
