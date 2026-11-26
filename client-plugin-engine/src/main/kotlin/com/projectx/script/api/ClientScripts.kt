package com.projectx.script.api

import com.projectx.game.bootstrap.GameThread
import com.projectx.game.cs2.CS2Executor
import com.projectx.game.cs2.CS2Executor.Cs2Result
import kotlinx.coroutines.future.await
import world.gregs.voidps.gameval.Gameval
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** How long [runClientScriptForResult] waits for a queued run before giving up. */
const val CLIENT_SCRIPT_TIMEOUT_MS = 2_000L

private val SCRIPT_KINDS = listOf("proc", "clientscript")

/** True once the engine can run clientscripts, which is after the client has run one of its own since injection. */
val clientScriptsReady: Boolean get() = CS2Executor.isReady()

/**
 * The id of the clientscript Jagex names [name], such as `"[proc,quicksort]"`, or just `"quicksort"`;
 * null when no script has that name.
 */
fun clientScriptId(name: String): Int? =
    Gameval.id(Gameval.CLIENTSCRIPT, name)
        ?: SCRIPT_KINDS.firstNotNullOfOrNull { Gameval.id(Gameval.CLIENTSCRIPT, "[$it,$name]") }

/**
 * Runs clientscript [scriptId] with [args] (each an `Int`, `Long` or `String`) on the next game tick, exactly as if
 * the client had called it, and returns at once. A script that acts on an interface sends the packets the client
 * would.
 */
fun runClientScript(scriptId: Int, vararg args: Any) = CS2Executor.executeScript(scriptId, *args)

/**
 * Runs clientscript [scriptId] with [args] and returns what it leaves on the stacks, its last value in `int`,
 * `long` or `string`. Null when clientscripts can't run yet or the run doesn't finish within
 * [CLIENT_SCRIPT_TIMEOUT_MS]. Suspends rather than blocking, so it is safe to call from any script.
 */
@JvmSynthetic
suspend fun runClientScriptForResult(scriptId: Int, vararg args: Any): Cs2Result? {
    if (GameThread.isCurrent()) return CS2Executor.executeScriptForResultSync(scriptId, args.toList())
    val future = CS2Executor.queueForResult(scriptId, args.toList()) ?: return null
    return try {
        future.orTimeout(CLIENT_SCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
