package com.projectx.util.spec

import kotlin.test.AfterTest
import kotlin.test.Test

class LoggingSpecTest {

    private val home = IsolatedHome()
    private val scenarios = LoggingScenarios::class.java.name
    private val out = System.out
    private val err = System.err

    @AfterTest
    fun cleanUp() {
        System.setOut(out)
        System.setErr(err)
        home.close()
    }

    private fun withProjectxFolder(method: String) {
        home.projectx.mkdirs()
        home.run(scenarios, method)
    }

    @Test
    fun `write to file appends CRLF lines stamped with the date`() = withProjectxFolder("writeToFileAppendsStampedLines")

    @Test
    fun `write to file creates a missing subfolder`() = withProjectxFolder("writeToFileCreatesOneSubfolder")

    @Test
    fun `errors go to errorLog dot log with the thread name and stack trace`() = withProjectxFolder("errorLogHoldsThreadAndStackTrace")

    @Test
    fun `log prints the tag in brackets before the message`() = home.run(scenarios, "logPrintsTaggedLines")

    @Test
    fun `handle writes the error log and prints to standard error`() = withProjectxFolder("handleLogsToFileAndStandardError")

    @Test
    fun `date string is month day year then time`() = home.run(scenarios, "dateStringFormat")

    @Test
    fun `write to file never throws, even without a projectx folder`() = home.run(scenarios, "writeToFileWithoutFolder")

    @Test
    fun `engine log stamps standard output and error into a per process file and a bounded tail`() =
        home.run(scenarios, "engineLogCapturesStandardStreams")
}
