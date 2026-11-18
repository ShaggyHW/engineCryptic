package com.projectx.store

import com.google.gson.JsonParser
import com.projectx.store.Instances.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstancesTest {
    private fun next(current: State, status: Int?, error: String? = null, local: Boolean = false) = Instances.next(current, status, error, local)

    @Test
    fun `a granted claim holds a slot`() = assertEquals(State.HELD, next(State.IDLE, 200))

    @Test
    fun `a claim with every slot taken is refused`() = assertEquals(State.FULL, next(State.IDLE, 409, "no_instance_available"))

    @Test
    fun `a claim fails closed when the site can't be reached`() = assertEquals(State.UNREACHABLE, next(State.IDLE, null))

    @Test
    fun `a store on this machine lets a claim through when it is down`() = assertEquals(State.HELD, next(State.IDLE, null, local = true))

    @Test
    fun `a heartbeat keeps its slot through an outage or a server error`() {
        assertEquals(State.HELD, next(State.HELD, null))
        assertEquals(State.HELD, next(State.HELD, 502))
    }

    @Test
    fun `stop from the website is obeyed, and told apart from a full account`() {
        assertEquals(State.STOPPED, next(State.HELD, 409, "stopped"))
        assertEquals(State.FULL, next(State.HELD, 409, "no_instance_available"))
    }

    @Test
    fun `a rejected sign-in needs signing in again`() = assertEquals(State.SIGN_IN_EXPIRED, next(State.HELD, 401))

    @Test
    fun `an instance goes back two minutes after the last script stops, not before`() {
        val stopped = 1_000_000L
        assertFalse(Instances.idleLongEnough(0L, stopped + 600_000))
        assertFalse(Instances.idleLongEnough(stopped, stopped + 119_000))
        assertTrue(Instances.idleLongEnough(stopped, stopped + 120_000))
    }

    private fun answer(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `granted seats are read from the answer, and a malformed one grants nothing`() {
        assertEquals(setOf("Telos", "Plain"), Instances.grantedIn(answer("""{"granted":["Telos","Plain"],"refused":[]}""")))
        assertEquals(emptySet(), Instances.grantedIn(answer("""{"refused":[{"internalName":"Telos","used":3,"total":3}]}""")))
    }

    @Test
    fun `a running script whose seat is taken back stops, others carry on`() {
        val asked = setOf("Telos", "Zuk")
        assertEquals(setOf("Zuk"), Instances.revoked(asked, granted = setOf("Telos"), running = listOf("Telos", "Zuk", "Woodcutter")))
    }

    @Test
    fun `a script started after the seats were asked for is not stopped by that answer`() {
        assertEquals(emptySet(), Instances.revoked(setOf("Telos"), granted = setOf("Telos"), running = listOf("Telos", "Zuk")))
    }
}
