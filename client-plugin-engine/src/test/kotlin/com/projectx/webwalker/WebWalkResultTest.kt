package com.projectx.webwalker

import com.projectx.traversal.pathing.NavPathClient
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebWalkResultTest {

    @Test
    fun `a walk that found no planner says so`() {
        assertTrue(WebWalkResult(WebWalkStatus.NO_PATH, "No route: ${NavPathClient.NOT_RUNNING}").plannerUnavailable)
        assertTrue(WebWalkResult(WebWalkStatus.NO_PATH, "No route: ${NavPathClient.NOT_RUNNING} (ConnectException)").plannerUnavailable)
    }

    @Test
    fun `other failures and arrivals are not a missing planner`() {
        assertFalse(WebWalkResult(WebWalkStatus.NO_PATH, "No route: the goal is walled off").plannerUnavailable)
        assertFalse(WebWalkResult(WebWalkStatus.ARRIVED, "Arrived at the bank").plannerUnavailable)
        assertFalse(WebWalkResult(WebWalkStatus.STUCK, NavPathClient.NOT_RUNNING).plannerUnavailable)
    }
}
