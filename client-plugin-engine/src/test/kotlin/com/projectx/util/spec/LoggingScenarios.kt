package com.projectx.util.spec

import com.projectx.util.EngineLog
import com.projectx.util.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Run only through [IsolatedHome]. */
class LoggingScenarios {

    private val projectx = File(System.getProperty("user.home"), ".projectx")
    private val stampedLine = Regex("""^\[\d{2}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}]: {2}""")

    private fun captured(set: (PrintStream) -> Unit, block: () -> Unit): String {
        val out = System.out
        val err = System.err
        val buffer = ByteArrayOutputStream()
        set(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(out)
            System.setErr(err)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    fun writeToFileAppendsStampedLines() {
        Logger.writeToFile("spec.log", "first")
        Logger.writeToFile("spec.log", "second line")
        val text = File(projectx, "spec.log").readText()
        val lines = text.split("\r\n")
        assertEquals(3, lines.size, text)
        assertEquals("", lines[2])
        assertTrue(stampedLine.containsMatchIn(lines[0]) && lines[0].endsWith("]:  first"), lines[0])
        assertTrue(stampedLine.containsMatchIn(lines[1]) && lines[1].endsWith("]:  second line"), lines[1])
    }

    fun writeToFileWithoutFolder() {
        assertTrue(!projectx.exists())
        Logger.writeToFile("spec.log", "nowhere to go")
        Logger.writeToFile("a/b/c.log", "nested")
    }

    fun writeToFileCreatesOneSubfolder() {
        Logger.writeToFile("sub/inner.log", "hello")
        assertTrue(File(projectx, "sub/inner.log").readText().endsWith("]:  hello\r\n"))
    }

    fun errorLogHoldsThreadAndStackTrace() {
        Logger.logError(IllegalStateException("spec failure"))
        Logger.logError("plain message")
        val text = File(projectx, "errorLog.log").readText()
        val thread = Thread.currentThread().name
        assertTrue(text.contains("]:  $thread: java.lang.IllegalStateException: spec failure"), text)
        assertTrue(text.contains("\tat "), text)
        assertTrue(text.contains("]:  plain message\r\n"), text)
    }

    fun logPrintsTaggedLines() {
        val out = captured({ System.setOut(it) }) {
            Logger.log("Tag", "message one")
            Logger.log(this, 42)
        }
        assertEquals(listOf("[Tag] message one", "[LoggingScenarios] 42"), out.lines().filter { it.isNotEmpty() })
    }

    fun handleLogsToFileAndStandardError() {
        val err = captured({ System.setErr(it) }) {
            Logger.handle(RuntimeException("handled spec"))
        }
        assertTrue(err.contains("ERROR! THREAD NAME: ${Thread.currentThread().name}"), err)
        assertTrue(err.contains("java.lang.RuntimeException: handled spec"), err)
        assertTrue(File(projectx, "errorLog.log").readText().contains("java.lang.RuntimeException: handled spec"))
    }

    fun dateStringFormat() {
        assertTrue(Regex("""\d{2}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}""").matches(Logger.getDateString()), Logger.getDateString())
    }

    fun engineLogCapturesStandardStreams() {
        assertNull(EngineLog.file)
        EngineLog.install()
        try {
            val file = assertNotNull(EngineLog.file)
            assertEquals(File(projectx, "logs").canonicalFile, file.parentFile.canonicalFile)
            val pid = ProcessHandle.current().pid()
            assertTrue(Regex("""projectx-\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{3}-$pid\.log""").matches(file.name), file.name)

            println("spec out line")
            System.err.println("spec err line")
            println(12345 as Any)

            val stamp = """\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}] """
            val lines = EngineLog.lines.toList()
            assertTrue(Regex("""Logging Initialized at \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""").matches(lines.first()), lines.first())
            assertTrue(lines.any { Regex("${stamp}\\[Engine] commit .+ built .+ pid $pid").matches(it) }, lines.toString())
            assertTrue(lines.any { Regex("${stamp}spec out line").matches(it) }, lines.toString())
            assertTrue(lines.any { Regex("${stamp}spec err line").matches(it) }, lines.toString())
            assertTrue(lines.any { Regex("${stamp}12345").matches(it) }, lines.toString())

            val written = file.readText()
            assertTrue(Regex("${stamp}spec out line").containsMatchIn(written), written)
            assertTrue(Regex("${stamp}spec err line").containsMatchIn(written), written)
            assertTrue(!written.contains("Logging Initialized"), written)

            repeat(EngineLog.MAX_LINES + 30) { println("filler $it") }
            assertEquals(100, EngineLog.MAX_LINES)
            assertEquals(EngineLog.MAX_LINES, EngineLog.lines.size)
            assertTrue(EngineLog.lines.last().endsWith("filler ${EngineLog.MAX_LINES + 29}"))
        } finally {
            EngineLog.close()
        }
        val closedFile = assertNotNull(EngineLog.file)
        println("after close")
        assertTrue(!closedFile.readText().contains("after close"))
    }
}
