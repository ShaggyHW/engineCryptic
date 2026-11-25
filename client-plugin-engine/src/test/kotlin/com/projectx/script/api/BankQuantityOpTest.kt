package com.projectx.script.api

import kotlin.test.Test
import kotlin.test.assertEquals

class BankQuantityOpTest {

    @Test
    fun `fixed amounts use their own ops`() {
        assertEquals(2, BankQuantity.op(1))
        assertEquals(3, BankQuantity.op(5))
        assertEquals(4, BankQuantity.op(10))
        assertEquals(7, BankQuantity.op(0))
    }

    @Test
    fun `fifty and other amounts go through X, never the last-X op`() {
        for (amount in listOf(2, 14, 28, 50, 100, 1000)) assertEquals(-1, BankQuantity.op(amount), "amount $amount")
    }
}
