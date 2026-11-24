package com.projectx.traversal.pathing.mappers

import com.projectx.script.Script
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.isDialogOpen
import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.nodes.TraversalSupport

/** Picks [answer] in the prompt the node before it put up; asks for another route when none comes or it lacks the answer. */
internal class PromptAnswerNode(private val answer: String, private val customReached: (() -> Boolean)?) : TraversalNode() {
    private var waitingSince = 0L
    private var answered = false

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) return false
        if (waitingSince == 0L) waitingSince = System.currentTimeMillis()
        if (isDialogOpen()) {
            if (continueDialogueContaining(answer)) {
                answered = true
                TraversalSupport.delayUntil(LANDING_MS) { traversalStopRequested() || reached(script) }
                return true
            }
            println("[PromptAnswer] the prompt offers no \"$answer\"")
        } else if (System.currentTimeMillis() - waitingSince < PROMPT_TIMEOUT_MS) {
            TraversalSupport.delayUntil(PROMPT_TIMEOUT_MS) { traversalStopRequested() || isDialogOpen() || reached(script) }
            return true
        }
        if (!requestTraversalRepath()) failTraversal("no prompt to answer \"$answer\" $this")
        return false
    }

    override fun reached(script: Script): Boolean = customReached?.invoke() ?: (answered && !isDialogOpen())

    override fun copy(): TraversalNode = PromptAnswerNode(answer, customReached)

    override fun toString(): String = "[PromptAnswer \"$answer\"]"

    private companion object {
        const val PROMPT_TIMEOUT_MS = 4_000L
        const val LANDING_MS = 12_000L
    }
}

internal fun TraversalBuilder<*>.answerPrompt(answer: String, reached: (() -> Boolean)?) = add(PromptAnswerNode(answer, reached))
