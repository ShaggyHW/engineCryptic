package com.projectx.script

import com.projectx.script.event.Event
import kotlinx.coroutines.CancellationException

abstract class StateMachineScript<T : StateMachineScript<T>> : Script() {
    protected var currentState: State<T>

    abstract fun getStartState(): State<T>

    init {
        currentState = getStartState()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun loop() {
        try {
            (currentState.checkNextState(this as T))?.let {
                currentState = it
                return
            }
            currentState.loop(this)
        } catch (cancelled: CancellationException) {
            // shouldInterrupt cancelled the pass: not an error, and swallowing it would let the pass run on.
            throw cancelled
        } catch (exception: Throwable) {
            exception.printStackTrace()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun onEvent(event: Event) {
        currentState.onEvent(this as T, event)
    }

    /** An override must call super, or the current state's [State.stateTickLoop] stops running. */
    @Suppress("UNCHECKED_CAST")
    override fun onTickLoop() {
        currentState.tickLoop(this as T)
    }
}

abstract class State<T : StateMachineScript<T>>() {
    abstract suspend fun T.checkNext(): State<T>?
    abstract suspend fun T.stateLoop()
    open fun T.onStateEvent(event: Event) { }

    /** Called once per server tick while this is the current state, even while its pass waits. Never wait in it. */
    open fun T.stateTickLoop() { }

    @JvmSynthetic
    suspend fun checkNextState(script: T): State<T>? = script.checkNext()
    @JvmSynthetic
    suspend fun loop(script: T) = script.stateLoop()
    fun onEvent(script: T, event: Event) = script.onStateEvent(event)
    fun tickLoop(script: T) = script.stateTickLoop()
}